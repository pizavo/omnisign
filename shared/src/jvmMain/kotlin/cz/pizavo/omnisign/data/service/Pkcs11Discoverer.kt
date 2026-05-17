package cz.pizavo.omnisign.data.service

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_KIT_PROXY_PATHS
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.P11_STANDALONE_PATTERN
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer.Companion.PKCS11_NAME_PATTERNS
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.smartcardio.CardTerminal
import javax.smartcardio.TerminalFactory

/**
 * Discovers PKCS#11 middleware libraries available on the current system and resolves
 * them to physical token identities.
 *
 * Discovery is layered from most to least authoritative:
 * 1. OS-native sources ([discoverViaOs]) — PC/SC on Windows, `security`/`pluginkit` on macOS,
 *    p11-kit on Linux.
 * 2. App-data drop directory — any PKCS#11-named file placed under
 *    `<appDataDir>/omnisign/pkcs11/`.
 * 3. User-supplied paths — entries from
 *    [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries].
 *
 * Duplicates are resolved first by canonical path, then by probing the actual hardware
 * token identity (label and serial number) via `C_GetTokenInfo`.  Multiple middleware DLLs
 * that report the same physical token serial produce a single [TokenInfo].
 *
 * @property crashBlacklist Records libraries whose subprocess validation crashed.  A path is
 *   skipped by [probeLibrary] only while it has crashed at least the [Pkcs11CrashBlacklist]
 *   threshold times within its sliding window; the record then decays and probing resumes,
 *   so a transient SafeNet-style crash does not disable a healthy library for the JVM
 *   lifetime.  Populated by [Pkcs11WarmupService] during startup validation.  All actual
 *   token probing runs out-of-process via [prober] — there is intentionally no in-process
 *   JNA consumer alongside SunPKCS11, which DSS uses for signing.
 * @property discoveryGate Concurrency gate ensuring that at most one [discoverTokens] cycle runs
 *   at a time, with at most one additional cycle queued.  When a discovery is already in progress
 *   and a new request arrives, the running cycle's result is discarded and a fresh cycle executes
 *   so every caller receives the latest hardware state.  Defaults to a new instance for backward
 *   compatibility and tests.
 * @property prober Strategy for probing PKCS#11 libraries for hardware token identities,
 *   process-isolated by default ([Pkcs11SubprocessProber]) so a native crash (SIGSEGV) cannot
 *   take down the host JVM.  Injected as a [Pkcs11Prober] so it can be substituted in tests.
 * @property probeParallelism Maximum number of subprocesses [discoverTokens] is allowed to
 *   spawn concurrently.  Each subprocess cold-starts a JVM and calls `C_Initialize` on the
 *   target library; running too many in parallel against the same vendor library (e.g. SafeNet
 *   `eTPKCS11.dll`) is a well-documented source of intermittent SIGSEGV / `CKR_FUNCTION_FAILED`
 *   responses.  Defaults to `2`, matching [Pkcs11WarmupService] so combined warmup +
 *   discovery never exceed `4` concurrent probes against the same lib in the worst case.
 * @property pcscRecovery Recovers the JDK's process-wide PC/SC context after the
 *   `sun.security.smartcardio` stale-context defect so [discoverViaPcsc] can re-enumerate
 *   readers within the same JVM session instead of returning empty until restart.  See
 *   [PcscContextRecovery].
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
	private val crashBlacklist: Pkcs11CrashBlacklist = Pkcs11CrashBlacklist(),
	private val discoveryGate: ConflatedProbeGate<List<TokenInfo>> = ConflatedProbeGate(),
	private val prober: Pkcs11Prober = Pkcs11SubprocessProber(),
	private val probeParallelism: Int = DEFAULT_PROBE_PARALLELISM,
	private val pcscRecovery: PcscContextRecovery = PcscContextRecovery(),
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
	 * Per-path cache of successful probe results.
	 *
	 * Populated by [probeLibrary] on subprocess success and by [primeCache] when the warmup
	 * service has a fresh result to share.  Lookups in [probeLibrary] short-circuit any
	 * present entry without spawning a subprocess.
	 *
	 * Entries live indefinitely; freshness is enforced by [Pkcs11CacheInvalidator] reacting
	 * to PC/SC reader-state events, not by a time-based TTL.  Failed probes are deliberately
	 * **not** cached: the user might insert the card mid-session and we want the next dialog
	 * open to see it.  The crash blacklist handles the "library always crashes" case
	 * separately.
	 */
	private val probeCache = ConcurrentHashMap<String, List<Pkcs11TokenIdentity>>()
	
	/**
	 * Cache of [collectCandidates] results, keyed by `(appDataPkcs11Dir, userPkcs11Libraries)`.
	 *
	 * `collectCandidates` is the cold dominator of every dialog open: PC/SC enumeration on
	 * Windows, and the p11-kit proxy on Linux (plus `security` / `pluginkit` on macOS).
	 * The composition of installed PKCS#11 middleware changes only when the user
	 * installs/uninstalls a vendor package or plugs / unplugs a smart-card reader.  Entries
	 * live indefinitely and are cleared by [Pkcs11CacheInvalidator] on reader plug / unplug
	 * events; explicit user rescan calls [invalidateCache].
	 */
	private val candidateCache = ConcurrentHashMap<CandidateCacheKey, List<Pair<String, String>>>()
	
	/**
	 * Composite cache key reflecting the inputs that determine the candidate list.
	 *
	 * @property dropDirPath Absolute path of the app-data drop directory, or `null` when none.
	 *   Two opens with different drop directories must not share a cache entry.
	 * @property userLibraryPaths Snapshot of user-supplied library paths (by absolute path,
	 *   names dropped because they don't affect candidate selection).
	 */
	private data class CandidateCacheKey(
		val dropDirPath: String?,
		val userLibraryPaths: List<String>,
	)
	
	/**
	 * Probe a single PKCS#11 library for token identities, using the cache when possible.
	 *
	 * Resolution order:
	 * 1. **Crash blacklist** — if the path has crashed enough times in the current sliding
	 *    window ([Pkcs11CrashBlacklist]), return empty without spawning anything.
	 * 2. **Cache hit** — if a successful probe result is present, return its identities
	 *    without spawning a subprocess.  This is the hot path that makes a second
	 *    `probeTokenPresent` call inside the dialog-open flow effectively free.  Cache
	 *    freshness is enforced by [Pkcs11CacheInvalidator] reacting to PC/SC events.
	 * 3. **Cache miss** — probe out-of-process via [prober] (default: process-isolated to
	 *    contain native SIGSEGV).  On non-empty success, populate the cache.  On empty
	 *    success or failure, leave the cache untouched so a later card insertion is picked
	 *    up on the next call.
	 *
	 * Out-of-process probing remains the cohabitation-safe choice alongside SunPKCS11 —
	 * see the class-level docs for the rationale behind the in-process path's removal.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library to probe.
	 * @return Token identities found in the library, or an empty list when the library is
	 *   blacklisted, unreachable, the probe times out, or no tokens are inserted.
	 */
	fun probeLibrary(libraryPath: String): List<Pkcs11TokenIdentity> {
		if (crashBlacklist.isCrashed(libraryPath)) {
			logger.debug { "Skipping crashed library '$libraryPath'" }
			return emptyList()
		}
		probeCache[libraryPath]?.let {
			logger.debug { "Probe cache hit for '$libraryPath' (${it.size} identity(-ies))" }
			return it
		}
		val identities = prober.probeIdentities(libraryPath)
		if (identities.isNotEmpty()) {
			probeCache[libraryPath] = identities
		}
		return identities
	}
	
	/**
	 * Insert an externally-computed probe result into the cache.
	 *
	 * Used by [Pkcs11WarmupService] to share its successful warmup probes with discovery so
	 * the very first sign-dialog open does not re-spawn subprocesses for libraries the
	 * warmup just validated.  No-op when [identities] is empty — empty results are never
	 * cached, so a freshly inserted card is still picked up.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 * @param identities Identities observed during the priming probe.
	 */
	fun primeCache(libraryPath: String, identities: List<Pkcs11TokenIdentity>) {
		if (identities.isEmpty()) return
		probeCache[libraryPath] = identities
		logger.debug { "Probe cache primed for '$libraryPath' (${identities.size} identity(-ies))" }
	}
	
	/**
	 * Drop every cached probe result so the next [probeLibrary] call re-spawns a subprocess.
	 *
	 * Used when the set of *present tokens* may have changed but the set of installed
	 * libraries has not — typically a card insertion or removal observed via
	 * [PcscMonitorService.events].
	 */
	fun invalidateProbes() {
		probeCache.clear()
		logger.debug { "Probe cache cleared" }
	}
	
	/**
	 * Drop every cached candidate enumeration so the next [collectCandidates] call
	 * re-runs the OS-native discovery branches.
	 *
	 * Used when the set of installed PKCS#11 libraries may have changed — typically a
	 * smart-card reader being plugged in or unplugged, which can alter PC/SC + Calais
	 * mappings or which p11-kit modules are reachable.
	 */
	fun invalidateCandidates() {
		candidateCache.clear()
		logger.debug { "Candidate cache cleared" }
	}
	
	/**
	 * Drop every cached probe and candidate result.
	 *
	 * Intended for explicit user-initiated refresh (a "Rescan tokens" button) and for tests.
	 * Event-driven invalidation typically uses the finer-grained [invalidateProbes] or
	 * [invalidateCandidates] instead, depending on which surface actually changed.
	 */
	fun invalidateCache() {
		invalidateProbes()
		invalidateCandidates()
	}
	
	/**
	 * Enumerate all unique candidate PKCS#11 library paths without probing them.
	 *
	 * This is the discovery-only first phase: OS-native sources, the app-data drop directory,
	 * and user-supplied libraries are merged and deduplicated by absolute path.  No
	 * subprocess is spawned (other than the OS-discovery sub-helpers' own spawns, which are
	 * themselves rate-limited and gated on prerequisites) and no PKCS#11 function is called.
	 *
	 * Used by [Pkcs11WarmupService] to obtain the candidate set for background initialization
	 * and by [discoverTokens] as the first step before parallel probing.
	 *
	 * Repeated calls with the same `(appDataPkcs11Dir, userPkcs11Libraries)` return the
	 * cached list without re-running PC/SC, registry, or directory scans; this is the
	 * dominant cost on every dialog open and the cache turns second-and-onward opens into
	 * a sub-millisecond lookup.  Cache entries live indefinitely; [Pkcs11CacheInvalidator]
	 * clears them on reader plug / unplug events, and [invalidateCache] handles explicit
	 * user rescans.
	 *
	 * @param appDataPkcs11Dir Optional drop directory; every PKCS#11-named file found here is
	 *   added to the candidate list without any config change.
	 * @param userPkcs11Libraries Additional `(display name, path)` pairs supplied by the user.
	 *   Only entries whose file exists on disk are included.
	 * @return Deduplicated list of `(display name, absolute path)` pairs.
	 */
	fun collectCandidates(
		appDataPkcs11Dir: File? = null,
		userPkcs11Libraries: List<Pair<String, String>> = emptyList(),
	): List<Pair<String, String>> {
		val cacheKey = CandidateCacheKey(
			dropDirPath = appDataPkcs11Dir?.absolutePath,
			userLibraryPaths = userPkcs11Libraries.map { it.second },
		)
		candidateCache[cacheKey]?.let {
			logger.debug { "Candidate cache hit (${it.size} entry(-ies))" }
			return it
		}
		val os = System.getProperty("os.name").lowercase()
		val jvmIs64Bit = System.getProperty("sun.arch.data.model") == "64"
		val candidates = collectCandidatesUncached(os, jvmIs64Bit, appDataPkcs11Dir, userPkcs11Libraries)
		candidateCache[cacheKey] = candidates
		return candidates
	}
	
	/**
	 * Compute the candidate list from scratch, bypassing [candidateCache].
	 *
	 * Extracted from [collectCandidates] so that the cache lookup wraps a single uncached
	 * call site, and so warmup can offer to populate the cache without going through the
	 * cache-lookup branch itself (the warmup result IS the first cache fill).
	 */
	private fun collectCandidatesUncached(
		os: String,
		jvmIs64Bit: Boolean,
		appDataPkcs11Dir: File?,
		userPkcs11Libraries: List<Pair<String, String>>,
	): List<Pair<String, String>> {
		val seen = LinkedHashMap<String, Pair<String, String>>()
		
		fun merge(candidates: List<Pair<String, String>>) {
			for ((name, path) in candidates) {
				val key = File(path).absolutePath
				seen.putIfAbsent(key, name to path)
			}
		}
		
		merge(discoverViaOs(os, jvmIs64Bit))
		if (appDataPkcs11Dir != null && appDataPkcs11Dir.isDirectory) {
			merge(
				appDataPkcs11Dir
					.listFiles { f -> f.isFile && isPkcs11FileName(f.name) }
					?.map { f -> deriveMiddlewareName(f.absolutePath) to f.absolutePath }
					?: emptyList()
			)
		}
		merge(userPkcs11Libraries.filter { (_, path) -> File(path).exists() })
		
		return seen.values.filterNot { (_, path) -> isSpyLibrary(File(path).name) }
	}
	
	/**
	 * Discover all PKCS#11 tokens available on the system.
	 *
	 * Discovery never blocks on [Pkcs11WarmupService] completion.  Each candidate library is
	 * probed via [probeLibrary], which returns a cached probe result when one is present
	 * (the hot path once warmup or an invalidator-driven rediscovery has populated the
	 * cache) and otherwise spawns an out-of-process [prober] subprocess.  There is no
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
				val candidates = collectCandidates(appDataPkcs11Dir, userPkcs11Libraries)

				val gate = Semaphore(probeParallelism.coerceAtLeast(1))
				val probeResults = coroutineScope {
					candidates.map { (name, path) ->
						async(Dispatchers.IO) {
							gate.withPermit { Triple(name, path, probeLibrary(path)) }
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
	 * earlier discovery cycles (warmup, [discoverTokens], or [primeCache]) populated and runs
	 * them through [buildTokenInfoList] for the same serial-based dedup that [discoverTokens]
	 * applies.
	 *
	 * Intended for consumers that should never trigger their own discovery cycle — most
	 * notably [DssTokenService.discoverTokens] for the sign-dialog hot path.  Pair the call
	 * with a wait on [discoveryRunning] (`filter { !it }.first()`) so the cache is fully
	 * populated by any in-flight producer before being read.
	 *
	 * @return Token info entries built from probe results currently cached; empty when no
	 *   library has been probed successfully (cold start before warmup, or after
	 *   [invalidateProbes] when nothing has refilled the cache yet).
	 */
	fun getCachedTokens(): List<TokenInfo> {
		val probedCandidates = probeCache.entries.map { (path, identities) ->
			Triple(deriveMiddlewareName(path), path, identities)
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
		val sortedWithIdentities = withIdentities.sortedBy { isProxyPath(it.second) }
		
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
	
	/**
	 * Query OS-native sources for PKCS#11 middleware.
	 *
	 * Each platform delegates to its OS PKCS#11 manager — the standard registration mechanism
	 * a well-behaved vendor installer uses — and nothing else.  Libraries that bypass the
	 * OS manager (an installer that skipped registration, a manually-deployed library, an
	 * out-of-tree development build) reach discovery via the user-controlled escape hatches
	 * documented in [collectCandidates]: the app-data drop directory and the
	 * `customPkcs11Libraries` config entry.
	 *
	 * - **Windows**: PC/SC + Calais ATR mapping
	 *   (`SCardListReaders` → `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`).
	 * - **macOS**: `security list-smartcards`, `pluginkit -mAT com.apple.ctk.token`, plus the
	 *   p11-kit *proxy* if the user installed p11-kit via Homebrew.
	 * - **Linux**: the p11-kit *proxy* library.
	 *
	 * The proxy is a single PKCS#11 module that aggregates every module registered with
	 * p11-kit, so probing it loads the whole p11-kit registry in one subprocess instead of
	 * N — meaningful on multi-token systems and noticeable even with our parallelism cap of
	 * two.  It also honours `.module` directives a naive parser ignores (`disable-in:`,
	 * `enable-in:`, priority ordering, env-var overrides).  Every modern distribution that
	 * ships `.module` files also ships the proxy in the same package
	 * (Fedora `p11-kit`, Debian/Ubuntu `p11-kit-modules`, Arch `p11-kit`,
	 * Homebrew `p11-kit`), so the proxy is the OS-manager surface — direct `.module` parsing
	 * adds no realistic coverage.  Users with non-registering middleware drop the library
	 * into `<appData>/omnisign/pkcs11/` or add it to `customPkcs11Libraries`.
	 *
	 * The platform-specific branches are independent and run in parallel via short-lived
	 * worker threads.
	 *
	 * Never throws; returns an empty list when an OS mechanism is unavailable.
	 */
	internal fun discoverViaOs(
		os: String = System.getProperty("os.name").lowercase(),
		@Suppress("UNUSED_PARAMETER") jvmIs64Bit: Boolean = System.getProperty("sun.arch.data.model") == "64",
	): List<Pair<String, String>> {
		val branches: List<() -> List<Pair<String, String>>> = when {
			os.contains("win") -> listOf(
				{ discoverViaPcsc() },
			)
			
			os.contains("mac") -> listOf(
				{ discoverViaMacOsSecurity() },
				{ discoverViaP11KitProxy() },
			)
			
			else -> listOf(
				{ discoverViaP11KitProxy() },
			)
		}
		
		return runBranchesInParallel(branches)
	}
	
	/**
	 * Execute the selected platform branches concurrently and concatenate their results in
	 * the declared branch order.  The branch set is OS-dependent (one on Windows and Linux,
	 * two on macOS), not a fixed three.
	 *
	 * Uses raw daemon threads with `join` rather than coroutines so that this synchronous
	 * helper stays callable from non-suspending code paths (warmup, diagnostics, tests).  Each
	 * branch is fully self-contained and bounded; we accept the small per-call thread-creation
	 * overhead in exchange for keeping `collectCandidates` non-suspending.
	 *
	 * Branches that throw have their exception swallowed and treated as "no candidates from
	 * this source", matching the legacy synchronous behaviour where each branch was wrapped
	 * in `runCatching` internally.
	 */
	private fun runBranchesInParallel(
		branches: List<() -> List<Pair<String, String>>>,
	): List<Pair<String, String>> {
		@Suppress("UNCHECKED_CAST")
		val results = arrayOfNulls<List<Pair<String, String>>>(branches.size)
		val threads = branches.mapIndexed { index, branch ->
			Thread({
				results[index] = runCatching { branch() }.getOrDefault(emptyList())
			}, "pkcs11-discover-${index}").apply {
				isDaemon = true
				start()
			}
		}
		threads.forEach { it.join() }
		return results.filterNotNull().flatten()
	}
	
	/**
	 * Return `true` when [fileName] (base name only) looks like a PKCS#11 provider library.
	 *
	 * Patterns checked:
	 * - Exact substring matches against [PKCS11_NAME_PATTERNS] (e.g. `pkcs11`, `cryptoki`).
	 * - A standalone `p11` token that is **not** immediately followed by a digit, via
	 *   [P11_STANDALONE_PATTERN].  This prevents Microsoft Visual C++ runtime DLLs such as
	 *   `msvcp110.dll`, `vcamp110.dll`, and `vcomp110.dll` from being mistaken for PKCS#11
	 *   middleware — they all contain the three-character substring `p11` as part of the
	 *   version number `p110`.
	 *
	 * Known spy/debugging wrappers (e.g. `pkcs11-spy.so`) are excluded via [isSpyLibrary].
	 */
	internal fun isPkcs11FileName(fileName: String): Boolean {
		if (isSpyLibrary(fileName)) return false
		val lower = fileName.lowercase()
		return PKCS11_NAME_PATTERNS.any { lower.contains(it) } ||
				P11_STANDALONE_PATTERN.containsMatchIn(lower)
	}
	
	/**
	 * Return `true` when [fileName] (base name only) is a known PKCS#11 spy or debugging
	 * wrapper library rather than actual middleware.
	 *
	 * The OpenSC project ships `pkcs11-spy.so` / `pkcs11-spy.dll` which is a logging
	 * pass-through that requires the `PKCS11SPY` environment variable to point to the real
	 * PKCS#11 module.  Loading it without that variable set produces errors or hangs.
	 * Such libraries must never be probed or offered as signing tokens.
	 */
	internal fun isSpyLibrary(fileName: String): Boolean {
		val lower = fileName.lowercase()
		return SPY_LIBRARY_PATTERNS.any { lower.contains(it) }
	}
	
	/**
	 * Derive a human-readable middleware display name from an absolute [libraryPath].
	 * Falls back to the file's base name when no known vendor pattern matches.
	 */
	internal fun deriveMiddlewareName(libraryPath: String): String {
		val lower = libraryPath.lowercase()
		return when {
			lower.contains("etpkcs11") || lower.contains("etoken") ||
					lower.contains("/sac") || lower.contains("\\sac") -> "SafeNet eToken"
			
			lower.contains("gclib") || lower.contains("gemalto") ||
					lower.contains("idprime") -> "Thales/Gemalto IDPrime"
			
			lower.contains("ykcs11") -> "YubiKey (YKCS11)"
			lower.contains("opensc") -> "OpenSC"
			lower.contains("iidp11") || lower.contains("netid") -> "SecMaker Net iD"
			lower.contains("cmp11") || lower.contains("charismathics") -> "Charismathics PKCS#11"
			lower.contains("softhsm") -> "SoftHSM2"
			lower.contains("libck") -> "Cryptoki Library"
			lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy") -> "p11-kit Proxy"
			else -> libraryPath.substringAfterLast('/').substringAfterLast('\\')
		}
	}
	
	/**
	 * Return `true` when the given [path] refers to the p11-kit proxy PKCS#11 module.
	 *
	 * On Linux, [discoverViaOs] prefers the proxy when it's present — it's a single subprocess
	 * load that covers every p11-kit-registered module, with consistent slot IDs.  If the user
	 * also adds direct module paths via the app-data drop directory or `customPkcs11Libraries`,
	 * the proxy and a direct module may report the same physical token.  In that case
	 * [buildTokenInfoList] sorts proxy results last so the direct library's path wins,
	 * because direct paths typically come from explicit user intent and let us pin SunPKCS11
	 * to a vendor-specific slot ID.
	 */
	internal fun isProxyPath(path: String): Boolean {
		val lower = path.lowercase()
		return lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy")
	}
	
	
	/**
	 * List PC/SC smart card readers via [javax.smartcardio.TerminalFactory] and resolve the
	 * PKCS#11 middleware for each inserted card from
	 * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
	 *
	 * Returns an empty list when no smart card service is running, no readers are connected,
	 * or the platform PC/SC stack is unreachable.  Genuine per-reader failures (mute card,
	 * JNA registry load issues, unexpected connect errors) are logged at WARN with their
	 * stack trace and swallowed; the known stale-context churn during a probe is logged
	 * concisely at INFO and recovered elsewhere (the watcher and the custom-library path),
	 * so discovery degrades gracefully to the user-supplied escape hatches in
	 * [collectCandidates].
	 *
	 * The implementation uses [javax.smartcardio] (the same stack [PcscMonitorService] uses
	 * for hot-insert events) rather than direct `winscard.dll` JNA calls.  Both are equivalent
	 * on Windows, but the smartcardio path is more robust against JNA marshaling issues that
	 * have caused silent enumeration failures in the past.
	 */
	private fun discoverViaPcsc(): List<Pair<String, String>> {
		val terminals = listPcscTerminals()

		if (terminals.isEmpty()) {
			logger.info { "discoverViaPcsc: no PC/SC readers detected" }
			return emptyList()
		}
		
		logger.info { "discoverViaPcsc: ${terminals.size} reader(s) found: ${terminals.map { it.name }}" }
		val results = mutableListOf<Pair<String, String>>()
		
		for (terminal in terminals) {
			runCatching {
				if (!terminal.isCardPresent) {
					logger.info { "discoverViaPcsc: reader='${terminal.name}' has no card inserted" }
					return@runCatching
				}
				val card = terminal.connect("*")
				try {
					val atrHex = card.atr.bytes.joinToString("") { "%02X".format(it) }
					val resolvedPath = resolveFromAtr(atrHex)
					when {
						resolvedPath == null -> logger.warn { "discoverViaPcsc: reader='${terminal.name}' atr=$atrHex - no matching Pkcs11Lib in Calais registry" }
						!File(resolvedPath).exists() -> logger.warn { "discoverViaPcsc: reader='${terminal.name}' atr=$atrHex resolved to '$resolvedPath' but the file does not exist on disk" }
						else -> {
							logger.info { "discoverViaPcsc: reader='${terminal.name}' atr=$atrHex -> '$resolvedPath'" }
							results += deriveMiddlewareName(resolvedPath) to resolvedPath
						}
					}
				} finally {
					runCatching { card.disconnect(false) }
				}
			}.onFailure { e ->
				if (pcscRecovery.isStaleContext(e)) {
					logger.info { "discoverViaPcsc: reader='${terminal.name}' probe skipped — PC/SC context went stale during the probe (known service churn; recovered by the watcher and the custom-library path)" }
				} else {
					logger.warn(e) { "discoverViaPcsc: failed to probe reader '${terminal.name}'" }
				}
			}
		}
		
		logger.info { "discoverViaPcsc: returning ${results.size} candidate(s): ${results.map { it.second }}" }
		return results
	}

	/**
	 * Enumerate PC/SC readers, transparently recovering from the JDK
	 * `sun.security.smartcardio` stale-context defect.
	 *
	 * The first failure in a session is typically `SCARD_E_NO_SERVICE` (the Windows
	 * *Smart Card* service is demand-stopped when no reader is attached); the JDK then
	 * caches a dead context and every later `list()` throws `SCARD_E_SERVICE_STOPPED`
	 * for the rest of the session — including the user's manual rescan.  On that
	 * signature [pcscRecovery] clears the stale handle and the enumeration is retried
	 * exactly once.  `SCARD_E_NO_READERS_AVAILABLE` is the benign "no token plugged in"
	 * case — on the first attempt **and** after the reset retry — and degrades to a
	 * clean empty list logged at INFO (no warning / stack trace).  A genuinely
	 * persistent failure that survives the reset is logged at WARN with its stack trace
	 * and degrades to an empty list so discovery falls back to the user-supplied escape
	 * hatches in [collectCandidates].
	 */
	private fun listPcscTerminals(): List<CardTerminal> {
		return try {
			TerminalFactory.getDefault().terminals().list()
		} catch (e: Exception) {
			if (pcscRecovery.isStaleContext(e) && pcscRecovery.resetContext()) {
				logger.info { "discoverViaPcsc: stale PC/SC context detected — reset and retrying enumeration" }
				runCatching { TerminalFactory.getDefault().terminals().list() }
					.onFailure { retry ->
						if (pcscRecovery.causeChainContains(retry, PcscContextRecovery.NO_READERS_AVAILABLE)) {
							logger.info { "discoverViaPcsc: no PC/SC readers detected after context reset" }
						} else {
							logger.warn(retry) { "discoverViaPcsc: PC/SC enumeration still failing after context reset" }
						}
					}
					.getOrDefault(emptyList())
			} else if (pcscRecovery.causeChainContains(e, PcscContextRecovery.NO_READERS_AVAILABLE)) {
				emptyList()
			} else {
				logger.warn(e) { "discoverViaPcsc: PC/SC terminal enumeration failed" }
				emptyList()
			}
		}
	}

	/**
	 * Look up the PKCS#11 library for a card by its ATR hex string in
	 * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
	 *
	 * Prefers the subkey whose stored `ATR` exactly matches [atrHex]; falls back to the
	 * first subkey with an existing `Pkcs11Lib` path when no exact match is found.
	 * Returns null when the hive is inaccessible or no entry is found.
	 */
	private fun resolveFromAtr(atrHex: String): String? {
		val root = "SOFTWARE\\Microsoft\\Cryptography\\Calais\\SmartCards"
		runCatching {
			val subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, root)
			var fallback: String? = null
			
			for (subKey in subKeys) {
				runCatching {
					val values = Advapi32Util.registryGetValues(
						WinReg.HKEY_LOCAL_MACHINE, "$root\\$subKey"
					)
					val pkcs11 = (values["Pkcs11Lib"] ?: values["Crypto Provider"]) as? String
						?: return@runCatching
					if (fallback == null && File(pkcs11).exists()) fallback = pkcs11
					val atr = values["ATR"] as? String ?: return@runCatching
					if (atr.replace(" ", "").equals(atrHex, ignoreCase = true)) return pkcs11
				}
			}
			return fallback
		}
		return null
	}
	
	/**
	 * Detect the macOS CryptoTokenKit PKCS#11 shim (`/usr/lib/libctkpcscd.dylib`) when a
	 * smart card or CTK token extension is actually present.
	 *
	 * Uses `security list-smartcards` and `pluginkit -mAT com.apple.ctk.token`.
	 * The shim is only returned when at least one card or CTK extension is found, avoiding a
	 * spurious PKCS#11 slot on systems with no tokens.  Other middleware paths (OpenSC, etc.)
	 * are covered by [discoverViaP11KitProxy] when the user has installed p11-kit via
	 * Homebrew, or by the user-controlled escape hatches in [collectCandidates].
	 *
	 * Returns an empty list when neither tool is available or no tokens are present.
	 */
	private fun discoverViaMacOsSecurity(): List<Pair<String, String>> {
		val shimPath = "/usr/lib/libctkpcscd.dylib"
		if (!File(shimPath).exists()) return emptyList()
		
		val hasCard = runCatching {
			Runtime.getRuntime()
				.exec(arrayOf("security", "list-smartcards"))
				.inputStream.bufferedReader().readText().isNotBlank()
		}.getOrDefault(false)
		
		if (hasCard) return listOf("macOS Smart Card (PC/SC)" to shimPath)
		
		val ctkExtensions = runCatching {
			val output = Runtime.getRuntime()
				.exec(arrayOf("pluginkit", "-mAT", "com.apple.ctk.token"))
				.inputStream.bufferedReader().readText()
			Regex("""^\s*\+\s*(\S+)""", RegexOption.MULTILINE)
				.findAll(output).map { it.groupValues[1] }.toList()
		}.getOrDefault(emptyList())
		
		if (ctkExtensions.isEmpty()) return emptyList()
		return listOf("macOS CryptoTokenKit (${ctkExtensions.size} extension(s))" to shimPath)
	}
	
	/**
	 * Load the p11-kit proxy module if present on the system.
	 *
	 * The proxy is a single PKCS#11 library that aggregates every module registered with
	 * p11-kit, so loading it exposes all system-registered tokens through one entry point.
	 * Only the first existing path from [P11_KIT_PROXY_PATHS] is returned — multiple proxy
	 * installations on the same machine are uncommon and the serial-number deduplication
	 * in [discoverTokens] would collapse them anyway.
	 *
	 * Returns an empty list when the proxy library is not found; [discoverViaOs] then simply
	 * contributes no candidates from this source (users with non-registering middleware use
	 * the app-data drop directory or `customPkcs11Libraries`).
	 *
	 * @param proxyPaths Ordered list of candidate proxy paths; override for testing.
	 */
	internal fun discoverViaP11KitProxy(
		proxyPaths: List<String> = P11_KIT_PROXY_PATHS,
	): List<Pair<String, String>> =
		proxyPaths.firstOrNull { File(it).exists() }
			?.let { listOf("p11-kit Proxy" to it) }
			?: emptyList()
	
	private companion object {
		val logger = KotlinLogging.logger {}
		
		/**
		 * Default ceiling on concurrent subprocess probes spawned by [discoverTokens].
		 *
		 * Matches [Pkcs11WarmupService.DEFAULT_MAX_PARALLELISM] so the worst-case combined
		 * load (warmup + discovery against the same lib) is bounded at four concurrent
		 * `C_Initialize` calls — well below the failure threshold reported for SafeNet
		 * eToken middleware.
		 */
		const val DEFAULT_PROBE_PARALLELISM = 2
		
		/**
		 * Substring patterns that unambiguously identify PKCS#11 middleware filenames.
		 * The standalone `p11` token is deliberately absent; it is matched separately by
		 * [P11_STANDALONE_PATTERN] to avoid false positives from VC++ runtime version numbers.
		 */
		val PKCS11_NAME_PATTERNS = listOf(
			"pkcs11", "etpkcs", "gclib", "opensc", "iidp11", "cmp11",
			"softhsm", "libsac", "libck", "cryptoki", "ykcs11",
		)
		
		/**
		 * Substring patterns that identify PKCS#11 spy or debugging wrapper libraries.
		 *
		 * These libraries (e.g., OpenSC `pkcs11-spy.so`) are logging pass-throughs that
		 * require additional environment configuration (`PKCS11SPY`) to function.  Loading
		 * them without that configuration causes errors or hangs.
		 */
		val SPY_LIBRARY_PATTERNS = listOf("pkcs11-spy", "pkcs11spy", "p11-spy", "p11spy")
		
		/**
		 * Matches the standalone `p11` token in a lowercase filename when it is **not**
		 * immediately followed by a digit.  Examples that match: `libp11.so`, `p11-kit.so`,
		 * `p11.dll`.  Examples that do **not** match: `msvcp110.dll`, `vcamp110.dll`.
		 */
		val P11_STANDALONE_PATTERN = Regex("""p11(?!\d)""")
		
		/**
		 * Ordered candidate paths for the p11-kit proxy PKCS#11 module.
		 *
		 * The proxy aggregates all modules registered with p11-kit and exposes their slots
		 * through a single library entry point.  Paths cover the multiarch layouts used by
		 * Debian/Ubuntu, RPM-based distributions, and manual installations on Linux, plus
		 * Homebrew (`brew install p11-kit`) on Intel and Apple Silicon macOS.
		 */
		val P11_KIT_PROXY_PATHS = listOf(
			"/usr/lib/x86_64-linux-gnu/pkcs11/p11-kit-proxy.so",
			"/usr/lib/aarch64-linux-gnu/pkcs11/p11-kit-proxy.so",
			"/usr/lib64/pkcs11/p11-kit-proxy.so",
			"/usr/lib/pkcs11/p11-kit-proxy.so",
			"/usr/local/lib/pkcs11/p11-kit-proxy.so",
			"/opt/homebrew/lib/pkcs11/p11-kit-proxy.so",
		)
	}
}

/**
 * Probe a PKCS#11 [libraryPath] for the identities of all currently inserted tokens.
 *
 * Uses JNA to call `C_Initialize`, `C_GetSlotList(tokenPresent=CK_TRUE)`, and
 * `C_GetTokenInfo` to read the hardware token label and serial number from each
 * occupied slot.  This never calls `C_Login` and therefore never risks incrementing
 * a wrong-PIN counter.
 *
 * Designed to run **only inside a [Pkcs11ProbeWorker] subprocess** so any native crash
 * stays contained.  `C_Initialize` is idempotent (`CKR_CRYPTOKI_ALREADY_INITIALIZED` is
 * treated as success); `C_Finalize` is deliberately not called because the subprocess
 * exits immediately after printing the identities.
 *
 * Returns an empty list when the library cannot be loaded, no slots have tokens, or
 * any PKCS#11 call fails.
 */
internal fun probeTokenIdentities(libraryPath: String): List<Pkcs11TokenIdentity> = runCatching {
	@Suppress("UNCHECKED_CAST")
	val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
	val initRv = lib.C_Initialize(null).toLong()
	if (initRv != CKR_OK && initRv != CKR_CRYPTOKI_ALREADY_INITIALIZED) return emptyList()
	
	val countMem = Memory(Native.LONG_SIZE.toLong()).also { it.clear() }
	if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()
	val slotCount = countMem.getNativeLong(0).toLong().toInt()
	if (slotCount <= 0) return emptyList()
	
	val slotsMem = Memory((slotCount.toLong() * Native.LONG_SIZE))
	slotsMem.clear()
	countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
	if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()
	
	val results = mutableListOf<Pkcs11TokenIdentity>()
	for (i in 0 until slotCount) {
		val slotId = slotsMem.getNativeLong((i.toLong() * Native.LONG_SIZE))
		val tokenInfo = Memory(CK_TOKEN_INFO_SIZE.toLong())
		tokenInfo.clear()
		if (lib.C_GetTokenInfo(slotId, tokenInfo).toLong() != CKR_OK) continue
		
		val label = tokenInfo.getByteArray(CK_TOKEN_INFO_LABEL_OFFSET.toLong(), CK_TOKEN_INFO_LABEL_LEN)
			.trimPkcs11Field()
		val serial = tokenInfo.getByteArray(CK_TOKEN_INFO_SERIAL_OFFSET.toLong(), CK_TOKEN_INFO_SERIAL_LEN)
			.trimPkcs11Field()
		
		if (serial.isNotBlank()) {
			results += Pkcs11TokenIdentity(
				label = label.ifBlank { serial },
				serialNumber = serial,
				libraryPath = libraryPath,
				slotId = slotId.toLong(),
			)
		}
	}
	results
}.getOrDefault(emptyList())

/**
 * Batch size for `C_FindObjects` calls during no-login certificate enumeration.
 */
private const val PKCS11_FIND_BATCH = 64

/**
 * Hard ceiling on a single attribute's byte length; guards against a misbehaving
 * module returning an absurd `ulValueLen` (or `(CK_ULONG)-1` for sensitive attrs).
 */
private const val PKCS11_MAX_ATTR_BYTES = 4_000_000L

/**
 * Field count of a worker `CERT` line: `CERT`, slot, CKA_ID hex, label b64, DER b64.
 */
private const val CERT_LINE_FIELDS = 5

/**
 * Enumerate a PKCS#11 [libraryPath]'s certificate objects **without** `C_Login`.
 *
 * Opens a read-only (`CKF_SERIAL_SESSION`-only) session per token-present slot, finds
 * `CKO_CERTIFICATE` objects, and reads `CKA_VALUE` / `CKA_ID` / `CKA_LABEL`.  No PIN is
 * ever supplied, so this returns exactly the certificates that are public objects on the
 * token — the "Route A premise" check (`pkcs11-tool --list-objects --type cert` without
 * `--login`), performed through OmniSign's own JNA stack.
 *
 * Designed to run **only inside a [Pkcs11ProbeWorker] subprocess** so any native crash is
 * contained.  `C_Initialize` is idempotent; `C_Finalize` is intentionally skipped because
 * the subprocess exits immediately after printing.  Any failure (load, init, slot, session,
 * find) degrades to an empty list so it can never disturb the identity probe that ran first.
 *
 * `CK_ATTRIBUTE` is `{ CK_ULONG type; CK_VOID_PTR pValue; CK_ULONG ulValueLen; }`.  Windows
 * Cryptoki is `pack(1)` while LP64 (Linux/macOS) uses natural alignment, but with
 * `CK_ULONG == Native.LONG_SIZE` and the pointer `== Native.POINTER_SIZE` the field offsets
 * collapse to the same formula on both ABIs, so the offsets below are computed once.
 */
internal fun probeNoLoginCertificates(libraryPath: String): List<Pkcs11NoLoginCertRecord> = runCatching {
	@Suppress("UNCHECKED_CAST")
	val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
	val initRv = lib.C_Initialize(null).toLong()
	if (initRv != CKR_OK && initRv != CKR_CRYPTOKI_ALREADY_INITIALIZED) return emptyList()

	val ulong = Native.LONG_SIZE
	val ptr = Native.POINTER_SIZE
	val pValueOff = ulong.toLong()
	val lenOff = (ulong + ptr).toLong()
	val attrSize = (ulong + ptr + ulong).toLong()

	val countMem = Memory(ulong.toLong()).also { it.clear() }
	if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()
	val slotCount = countMem.getNativeLong(0).toLong().toInt()
	if (slotCount <= 0) return emptyList()
	val slotsMem = Memory(slotCount.toLong() * ulong).also { it.clear() }
	countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
	if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()

	val records = mutableListOf<Pkcs11NoLoginCertRecord>()
	for (i in 0 until slotCount) {
		val slotId = slotsMem.getNativeLong(i.toLong() * ulong)
		val sessMem = Memory(ulong.toLong()).also { it.clear() }
		if (lib.C_OpenSession(slotId, NativeLong(CKF_SERIAL_SESSION), null, null, sessMem)
				.toLong() != CKR_OK
		) continue
		val session = sessMem.getNativeLong(0)
		try {
			val classHolder = Memory(ulong.toLong()).also { it.setNativeLong(0, NativeLong(CKO_CERTIFICATE)) }
			val template = Memory(attrSize).also { it.clear() }
			template.setNativeLong(0, NativeLong(CKA_CLASS))
			template.setPointer(pValueOff, classHolder)
			template.setNativeLong(lenOff, NativeLong(ulong.toLong()))
			if (lib.C_FindObjectsInit(session, template, NativeLong(1)).toLong() != CKR_OK) continue

			val handles = Memory(ulong.toLong() * PKCS11_FIND_BATCH).also { it.clear() }
			val foundCount = Memory(ulong.toLong()).also { it.clear() }
			while (true) {
				if (lib.C_FindObjects(session, handles, NativeLong(PKCS11_FIND_BATCH.toLong()), foundCount)
						.toLong() != CKR_OK
				) break
				val n = foundCount.getNativeLong(0).toLong().toInt()
				if (n <= 0) break
				for (h in 0 until n) {
					val obj = handles.getNativeLong(h.toLong() * ulong)
					val der = readPkcs11Attribute(lib, session, obj, CKA_VALUE, pValueOff, lenOff, attrSize)
						?: continue
					val id = readPkcs11Attribute(lib, session, obj, CKA_ID, pValueOff, lenOff, attrSize)
						?: ByteArray(0)
					val label = readPkcs11Attribute(lib, session, obj, CKA_LABEL, pValueOff, lenOff, attrSize)
						?: ByteArray(0)
					records += Pkcs11NoLoginCertRecord(
						slotId = slotId.toLong(),
						ckaIdHex = id.joinToString("") { "%02x".format(it) },
						labelBase64 = Base64.getEncoder().encodeToString(label),
						derBase64 = Base64.getEncoder().encodeToString(der),
					)
				}
				if (n < PKCS11_FIND_BATCH) break
			}
			lib.C_FindObjectsFinal(session)
		} finally {
			lib.C_CloseSession(session)
		}
	}
	records
}.getOrDefault(emptyList())

/**
 * Read one object attribute with the standard two-pass `C_GetAttributeValue` idiom:
 * a NULL-buffer call to learn the length, then a sized call to fetch the bytes.
 *
 * Returns `null` when the attribute is absent, sensitive, or reports an out-of-range
 * length (including the `(CK_ULONG)-1` sentinel, which surfaces as a non-positive Long).
 */
private fun readPkcs11Attribute(
	lib: Pkcs11ProbeLib,
	session: NativeLong,
	obj: NativeLong,
	attrType: Long,
	pValueOff: Long,
	lenOff: Long,
	attrSize: Long,
): ByteArray? {
	val sizing = Memory(attrSize).also { it.clear() }
	sizing.setNativeLong(0, NativeLong(attrType))
	val sizeRv = lib.C_GetAttributeValue(session, obj, sizing, NativeLong(1)).toLong()
	if (sizeRv != CKR_OK && sizeRv != CKR_BUFFER_TOO_SMALL) return null
	val len = sizing.getNativeLong(lenOff).toLong()
	if (len <= 0L || len > PKCS11_MAX_ATTR_BYTES) return null

	val buffer = Memory(len)
	val fetch = Memory(attrSize).also { it.clear() }
	fetch.setNativeLong(0, NativeLong(attrType))
	fetch.setPointer(pValueOff, buffer)
	fetch.setNativeLong(lenOff, NativeLong(len))
	if (lib.C_GetAttributeValue(session, obj, fetch, NativeLong(1)).toLong() != CKR_OK) return null
	return buffer.getByteArray(0, len.toInt())
}

/**
 * Parse the `CERT\t<slot>\t<ckaIdHex>\t<labelBase64>\t<derBase64>` lines emitted by a
 * `--certs` [Pkcs11ProbeWorker] run into [Pkcs11NoLoginParsedCert]s.
 *
 * Non-`CERT` lines (token-identity output, stray logging) are ignored, and any line that
 * fails to split into five fields or whose Base64/DER does not parse as an X.509
 * certificate is dropped — the parser never throws.  Single source of truth for the
 * `CERT` line format, shared by the diagnostics report and no-login discovery.
 */
internal fun parseProbeNoLoginCerts(stdout: String): List<Pkcs11NoLoginParsedCert> {
	val factory = runCatching { CertificateFactory.getInstance("X.509") }.getOrNull() ?: return emptyList()
	return stdout.lines()
		.filter { it.startsWith("CERT\t") }
		.mapNotNull { line ->
			val parts = line.split('\t')
			if (parts.size != CERT_LINE_FIELDS) return@mapNotNull null
			val der = runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() ?: return@mapNotNull null
			val cert = runCatching {
				factory.generateCertificate(der.inputStream()) as X509Certificate
			}.getOrNull() ?: return@mapNotNull null
			Pkcs11NoLoginParsedCert(
				certificate = cert,
				ckaIdHex = parts[2],
				label = runCatching { String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8) }
					.getOrDefault(""),
				slotId = parts[1].toLongOrNull() ?: 0L,
			)
		}
}

/**
 * Project [parseProbeNoLoginCerts] into the diagnostics-report shape.
 */
internal fun parseProbeCertificates(stdout: String): List<Pkcs11DiagnosticsReport.RawNoLoginCert> =
	parseProbeNoLoginCerts(stdout).map { parsed ->
		Pkcs11DiagnosticsReport.RawNoLoginCert(
			subjectDN = parsed.certificate.subjectX500Principal.name,
			issuerDN = parsed.certificate.issuerX500Principal.name,
			serialNumber = parsed.certificate.serialNumber.toString(),
			ckaId = parsed.ckaIdHex,
			label = parsed.label,
			slotId = parsed.slotId,
		)
	}

/**
 * Normalize a PKCS#11 token serial number for deduplication comparison.
 *
 * Different middleware implementations may report the same physical serial with
 * different padding and casing — for example, SafeNet uses null-byte padding while
 * OpenSC uses space-padding, and some middleware upper-cases the hex serial while
 * others preserve the case from the card.  This function strips all whitespace and
 * null bytes and upper-cases the result so that `"ABC 123\u0000"` and `"abc123  "`
 * both normalize to `"ABC123"`.
 *
 * @param serial The raw serial string (already decoded from bytes, may contain
 *   residual whitespace or null-byte artifacts).
 * @return The normalized serial suitable for set-based deduplication.
 */
internal fun normalizeSerial(serial: String): String =
	serial.filterNot { it.isWhitespace() || it == '\u0000' }.uppercase()
