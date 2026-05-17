package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Discovers PKCS#11 tokens by composing candidate enumeration, process-isolated probing,
 * and serial-based deduplication.
 *
 * This is the thin orchestrator over three injected collaborators:
 * - [Pkcs11CandidateCollector] enumerates candidate middleware library paths from OS-native
 *   sources, the app-data drop directory, and user config.
 * - [Pkcs11ProbeCache] resolves each candidate to physical token identities, caching
 *   successful probes and skipping crash-blacklisted libraries.
 * - [buildTokenInfoList] collapses identities to one [TokenInfo] per physical token serial
 *   (direct paths preferred over the p11-kit proxy).
 *
 * @property probeCache Resolves a candidate library to token identities and owns the probe
 *   cache + crash blacklist + the process-isolated prober ([Pkcs11ProbeCache]).  Injected so
 *   warmup, the invalidator and the sign-dialog read path all share one cache instance.
 * @property candidateCollector Enumerates (and caches) the candidate library paths
 *   ([Pkcs11CandidateCollector]).  Shared so the invalidator can clear its cache on a PC/SC
 *   event and warmup can prime against the same candidate set the dialog will read.
 * @property discoveryGate Concurrency gate ensuring that at most one [discoverTokens] cycle runs
 *   at a time, with at most one additional cycle queued.  When a discovery is already in progress
 *   and a new request arrives, the running cycle's result is discarded and a fresh cycle executes
 *   so every caller receives the latest hardware state.  Defaults to a new instance for backward
 *   compatibility and tests.
 * @property probeParallelism Maximum number of subprocesses [discoverTokens] is allowed to
 *   spawn concurrently.  Each subprocess cold-starts a JVM and calls `C_Initialize` on the
 *   target library; running too many in parallel against the same vendor library (e.g. SafeNet
 *   `eTPKCS11.dll`) is a well-documented source of intermittent SIGSEGV / `CKR_FUNCTION_FAILED`
 *   responses.  Defaults to `2`, matching [Pkcs11WarmupService] so combined warmup +
 *   discovery never exceed `4` concurrent probes against the same lib in the worst case.
 *
 * Cache lifetime is **event-driven**, not time-driven: entries live indefinitely until
 * [Pkcs11CacheInvalidator] observes a PC/SC reader-state change and clears them, or until
 * a caller invokes [invalidateCache] explicitly (e.g. a "Rescan tokens" UI button for the
 * rare case of a vendor middleware install while the app is running).  This keeps hot-path
 * dialog opens at sub-millisecond cost without ever returning stale hardware state.
 *
 * Consumers that should not trigger their own discovery cycle (the sign dialog being the
 * canonical case) instead suspend on [discoveryRunning] until any in-flight cycle settles
 * — warmup at startup or invalidator-launched rediscovery after a PC/SC event — and then
 * read [getCachedTokens].  The producer side ([Pkcs11WarmupService], the invalidator) keeps
 * the flag accurate by wrapping its own discovery work in [beginDiscovery] / [endDiscovery];
 * [discoverTokens] does so internally on every caller's behalf.
 */
class Pkcs11Discoverer(
	private val probeCache: Pkcs11ProbeCache = Pkcs11ProbeCache(),
	private val candidateCollector: Pkcs11CandidateCollector = Pkcs11CandidateCollector(),
	private val discoveryGate: ConflatedProbeGate<List<TokenInfo>> = ConflatedProbeGate(),
	private val probeParallelism: Int = DEFAULT_PROBE_PARALLELISM,
	private val discoverySignal: Pkcs11DiscoverySignal = Pkcs11DiscoverySignal(),
) {

	/**
	 * `true` while at least one PKCS#11 discovery cycle is in flight.  Delegates to the
	 * shared [Pkcs11DiscoverySignal]; see there for the cross-producer contract and why the
	 * initial value is `false`.
	 */
	val discoveryRunning: StateFlow<Boolean> get() = discoverySignal.discoveryRunning

	/**
	 * Begin a discovery cycle.  Delegates to [Pkcs11DiscoverySignal.beginDiscovery] — pair
	 * every call with [endDiscovery] in a `finally` block to avoid leaking the flag.
	 */
	fun beginDiscovery() = discoverySignal.beginDiscovery()

	/**
	 * End a discovery cycle.  Delegates to [Pkcs11DiscoverySignal.endDiscovery].
	 */
	fun endDiscovery() = discoverySignal.endDiscovery()

	/**
	 * Drop every cached probe and candidate result.
	 *
	 * Intended for explicit user-initiated refresh (a "Rescan tokens" button) and for tests.
	 * Event-driven invalidation typically uses the finer-grained
	 * [Pkcs11ProbeCache.invalidateProbes] or [Pkcs11CandidateCollector.invalidateCandidates]
	 * instead, depending on which surface actually changed.
	 */
	fun invalidateCache() {
		probeCache.invalidateProbes()
		candidateCollector.invalidateCandidates()
	}

	/**
	 * Discover all PKCS#11 tokens available on the system.
	 *
	 * Discovery never blocks on [Pkcs11WarmupService] completion.  Candidates come from
	 * [Pkcs11CandidateCollector.collectCandidates]; each is probed via
	 * [Pkcs11ProbeCache.probeLibrary], which returns a cached probe result when one is
	 * present (the hot path once warmup or an invalidator-driven rediscovery has populated
	 * the cache) and otherwise spawns an out-of-process probe subprocess.  There is no
	 * in-process probing path.
	 *
	 * Probing runs in parallel on [Dispatchers.IO] — one coroutine per unique library path —
	 * so that slow or unresponsive middleware does not delay discovery of other tokens.
	 *
	 * Deduplication is described in [buildTokenInfoList]: serial-number collapse with
	 * direct-before-proxy ordering, and libraries with no identities are dropped (no stub
	 * `TokenInfo` is emitted for an empty probe).
	 *
	 * Concurrency between multiple callers is managed by [discoveryGate]: at most one
	 * full discovery cycle runs at a time, with at most one queued behind it.  If a new
	 * request arrives while a cycle is in progress, the in-progress result is discarded
	 * and a fresh cycle runs so that every caller receives the latest hardware state.
	 *
	 * @param appDataPkcs11Dir Optional drop directory; every PKCS#11-named file found here is
	 *   added to the candidate list without any config change.
	 * @param userPkcs11Libraries Additional `(display name, path)` pairs supplied by the user.
	 *   Only entries whose file exists on disk are included.
	 */
	suspend fun discoverTokens(
		appDataPkcs11Dir: File? = null,
		userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
	): List<TokenInfo> {
		beginDiscovery()
		try {
			return discoveryGate.runOrCoalesce {
				val candidates = candidateCollector.collectCandidates(appDataPkcs11Dir, userPkcs11Libraries)

				val gate = Semaphore(probeParallelism.coerceAtLeast(1))
				val probeResults = coroutineScope {
					candidates.map { (name, path) ->
						async(Dispatchers.IO) {
							gate.withPermit { Triple(name, path, probeCache.probeLibrary(path)) }
						}
					}.awaitAll()
				}

				buildTokenInfoList(probeResults)
			}
		} finally {
			endDiscovery()
		}
	}

	/**
	 * Return the deduplicated [TokenInfo] list reflecting the current [probeCache] contents.
	 *
	 * This is the **read-only** counterpart to [discoverTokens]: no subprocess is spawned, no
	 * candidate enumeration is run, no [discoveryGate] is engaged.  It walks the entries that
	 * earlier discovery cycles (warmup, [discoverTokens], or [Pkcs11ProbeCache.primeCache])
	 * populated and runs them through [buildTokenInfoList] for the same serial-based dedup
	 * that [discoverTokens] applies.
	 *
	 * Intended for consumers that should never trigger their own discovery cycle — most
	 * notably [DssTokenService.discoverTokens] for the sign-dialog hot path.  Pair the call
	 * with a wait on [discoveryRunning] (`filter { !it }.first()`) so the cache is fully
	 * populated by any in-flight producer before being read.
	 *
	 * @return Token info entries built from probe results currently cached; empty when no
	 *   library has been probed successfully (cold start before warmup, or after
	 *   [Pkcs11ProbeCache.invalidateProbes] when nothing has refilled the cache yet).
	 */
	fun getCachedTokens(): List<TokenInfo> {
		val probedCandidates = probeCache.cachedProbes().map { (path, identities) ->
			Triple(candidateCollector.deriveMiddlewareName(path), path, identities)
		}
		return buildTokenInfoList(probedCandidates)
	}

	/**
	 * Apply the deduplication strategy described in [discoverTokens] to a pre-computed list
	 * of probed candidates and emit the resulting [TokenInfo] entries.
	 *
	 * Extracted as an internal helper so [Pkcs11DiagnosticsService] can reuse the same dedup
	 * logic when building its own report from sequentially timed probes — keeping a single
	 * source of truth for the proxy-vs-direct ordering and serial normalisation.
	 *
	 * Dedup rules:
	 * 1. **Identities required** — only libraries that returned at least one token identity
	 *    contribute to the result.  Libraries that probe successfully but expose no inserted
	 *    token are surfaced only via diagnostics, not as user-visible tokens.
	 * 2. **Serial number** — the same normalised serial collapses to a single entry; direct
	 *    libraries are processed before proxy paths so direct paths win.
	 *
	 * @param probedCandidates Triples of `(display name, absolute path, identities)` —
	 *   one per candidate library, in any order.
	 * @return Deduplicated [TokenInfo] list ready to surface to the caller.
	 */
	internal fun buildTokenInfoList(
		probedCandidates: List<Triple<String, String, List<Pkcs11TokenIdentity>>>,
	): List<TokenInfo> {
		val withIdentities = probedCandidates.filter { it.third.isNotEmpty() }
		val sortedWithIdentities = withIdentities.sortedBy { candidateCollector.isProxyPath(it.second) }

		val result = mutableListOf<TokenInfo>()
		val seenSerials = mutableSetOf<String>()

		for ((_, path, identities) in sortedWithIdentities) {
			for (identity in identities) {
				if (seenSerials.add(normalizeSerial(identity.serialNumber))) {
					result += TokenInfo(
						id = "pkcs11-${identity.serialNumber}",
						name = identity.label,
						type = TokenType.PKCS11,
						path = path,
						requiresPin = true,
						pkcs11SlotId = identity.slotId,
					)
				}
			}
		}

		return result
	}

	private companion object {
		/**
		 * Default ceiling on concurrent subprocess probes spawned by [discoverTokens].
		 *
		 * Matches [Pkcs11WarmupService.DEFAULT_MAX_PARALLELISM] so the worst-case combined
		 * load (warmup + discovery against the same lib) is bounded at four concurrent
		 * `C_Initialize` calls — well below the failure threshold reported for SafeNet
		 * eToken middleware.
		 */
		const val DEFAULT_PROBE_PARALLELISM = 2
	}
}

/**
 * Normalize a PKCS#11 token serial number for deduplication comparison.
 *
 * Different middleware implementations may report the same physical serial with
 * different padding and casing — for example, SafeNet uses null-byte padding while
 * OpenSC uses space-padding, and some middleware upper-cases the hex serial while
 * others preserve the case from the card.  This function strips all whitespace and
 * null bytes and upper-cases the result so that a serial padded with trailing null
 * bytes and one padded with trailing spaces both normalize to the same value.
 *
 * @param serial The raw serial string (already decoded from bytes, may contain
 *   residual whitespace or null-byte artifacts).
 * @return The normalized serial suitable for set-based deduplication.
 */
internal fun normalizeSerial(serial: String): String =
	serial.filterNot { it.isWhitespace() || it.code == 0 }.uppercase()
