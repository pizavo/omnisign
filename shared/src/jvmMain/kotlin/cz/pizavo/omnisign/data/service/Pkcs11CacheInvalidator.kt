package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps the PKCS#11 probe and candidate caches in sync with PC/SC reader-state events.
 *
 * [Pkcs11ProbeCache] and [Pkcs11CandidateCollector] cache probe results and candidate
 * enumerations so repeated dialog opens are sub-millisecond.  Without event-driven
 * invalidation, a hot-inserted card is invisible until the cache is otherwise cleared.  By
 * subscribing to [PcscMonitorService.events], this service does two things on every
 * relevant change:
 *
 * 1. **Invalidate** the appropriate cache so any cached data that contradicts the new
 *    hardware state is dropped immediately: reader plug / unplug always clears both the
 *    probe and candidate caches; card insert / removal clears the probe cache, and also
 *    the candidate cache when [candidateCacheIsCardDependent] (Windows, where the
 *    candidate list is derived from the inserted card's ATR — see
 *    [Pkcs11CandidateCollector]).
 * 2. **Proactively re-run discovery** in the background so the cache is *populated* with
 *    fresh data by the time the user opens the sign dialog.  The result is intentionally
 *    discarded — its only purpose is the side effect on the shared probe / candidate caches.
 *
 * Cache-key consistency: the rediscovery cycle uses the **same** `(appDataPkcs11Dir,
 * userPkcs11Libraries)` tuple that [DssTokenService.discoverTokens] passes when the dialog
 * opens, so the populated cache entry is the one the dialog will look up.  User libraries
 * are re-read from [ConfigRepository] on every event because the config may change
 * mid-session.
 *
 * Concurrency: [Pkcs11Discoverer.discoverTokens] is gated by an internal
 * [ConflatedProbeGate] — at most one cycle runs at a time with at most one coalesced
 * pending slot, so a burst of insert / remove events collapses to at most two probe
 * rounds without any debouncing here.
 *
 * Lifecycle: the subscription self-starts at construction time on a [SupervisorJob]-backed
 * scope so collector failures never propagate out.  The [Dispatchers.IO] threads are
 * daemons, so the JVM is free to exit when no foreground work remains.  Each entry point
 * must resolve this class from Koin during bootstrap (e.g.
 * `koin.get<Pkcs11CacheInvalidator>()`) for the lazy `single` definition to be
 * instantiated and the subscription to begin.
 *
 * @property monitor The PC/SC monitor whose [PcscMonitorService.events] flow this service
 *   collects.  Collecting on the flow lazily starts the underlying watcher coroutine.
 * @property discoverer Drives the background [Pkcs11Discoverer.discoverTokens] rediscovery
 *   cycle and the explicit [Pkcs11Discoverer.invalidateCache] rescan; not used for the
 *   fine-grained per-event cache invalidation.
 * @property probeCache The shared probe cache cleared on every relevant PC/SC event so a
 *   hot-inserted card is re-probed on the next discovery cycle.
 * @property candidateCollector The shared candidate cache cleared on reader plug / unplug
 *   (and on card events where [candidateCacheIsCardDependent]) so a newly-installed or
 *   ATR-resolved library is re-enumerated.
 * @property configRepository Source of [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries],
 *   re-read on every event so config changes during the session are respected.
 * @property appDataPkcs11Dir Drop directory passed to [Pkcs11Discoverer.discoverTokens] —
 *   must match the directory used by [DssTokenService.discoverTokens] so the populated
 *   cache entry has the same key the dialog will look up.
 * @property scope Coroutine scope used both for the event collector and for proactively
 *   launched rediscovery cycles.  Defaults to a private [SupervisorJob]-backed
 *   [Dispatchers.IO] scope; tests override with a `TestScope` so they can advance
 *   coroutine completion deterministically.
 * @property candidateCacheIsCardDependent Whether inserting / removing a card can change
 *   which PKCS#11 libraries are *candidates* (not merely which tokens are present).  True
 *   on **Windows**, where [Pkcs11PcscCalaisResolver] resolves the middleware per inserted
 *   card from the card's ATR via the Calais registry, so a card event must also drop the
 *   candidate cache or a freshly-inserted card's token stays invisible until a reader
 *   replug or a manual rescan.  False on Linux (p11-kit proxy) and macOS, where the
 *   candidate set is card-independent and clearing it on every card event would be wasted
 *   PC/SC + registry re-enumeration.  Defaults to an `os.name` check; injectable so tests
 *   pin it deterministically rather than depending on the host OS.
 */
class Pkcs11CacheInvalidator(
	private val monitor: PcscMonitorService,
	private val discoverer: Pkcs11Discoverer,
	private val probeCache: Pkcs11ProbeCache,
	private val candidateCollector: Pkcs11CandidateCollector,
	private val configRepository: ConfigRepository,
	private val appDataPkcs11Dir: File,
	private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
	private val candidateCacheIsCardDependent: Boolean = System.getProperty("os.name").lowercase().contains("win"),
) : AutoCloseable {

	init {
		scope.launch {
			try {
				monitor.events.collect(::handleEvent)
			} catch (e: Throwable) {
				logger.warn(e) {
					"PC/SC event collector terminated; PKCS#11 cache invalidation will no longer respond to reader-state changes"
				}
			}
		}
		logger.info { "Pkcs11CacheInvalidator started — listening for PC/SC reader-state changes" }
	}

	/**
	 * Cancel the event-collection coroutine and any in-flight background rediscovery cycles.
	 *
	 * Mirrors [PcscMonitorService.close]: bounded-lifecycle hosts (Ktor's `testApplication`)
	 * call this on application stop so the collector does not survive into the next test,
	 * which otherwise accumulates one orphan coroutine per `testApplication { … }` and
	 * eventually OOMs the test JVM.
	 *
	 * After [close] the invalidator no longer reacts to PC/SC events — that is by design;
	 * the next application start constructs a fresh invalidator with its own scope. Safe
	 * to call multiple times; cancelling an already-cancelled scope is a no-op.
	 */
	override fun close() {
		scope.cancel()
	}

	/**
	 * Dispatch a single [PcscEvent] to the appropriate cache invalidation, then launch a
	 * background rediscovery cycle so the cache is repopulated before the next dialog open.
	 *
	 * Exposed at `internal` visibility so unit tests can drive the side effects
	 * synchronously without going through the [PcscMonitorService.events] [SharedFlow]
	 * buffer.  The launched rediscovery does not block this method — it returns as soon
	 * as the cache invalidation is recorded.
	 *
	 * @param event The PC/SC event to handle.
	 */
	internal fun handleEvent(event: PcscEvent) {
		when (event) {
			is PcscEvent.CardInserted, is PcscEvent.CardRemoved -> {
				logger.info {
					"$event → invalidating probe cache" +
						(if (candidateCacheIsCardDependent) " and candidate cache" else "") +
						" and triggering rediscovery"
				}
				probeCache.invalidateProbes()
				if (candidateCacheIsCardDependent) candidateCollector.invalidateCandidates()
			}
			is PcscEvent.ReaderConnected, is PcscEvent.ReaderDisconnected -> {
				logger.info { "$event → invalidating probe and candidate caches and triggering rediscovery" }
				probeCache.invalidateProbes()
				candidateCollector.invalidateCandidates()
			}
		}
		scope.launch { runRediscovery() }
	}

	/**
	 * Manually trigger a full rescan — invalidate every cache entry, then re-run discovery
	 * to repopulate it.
	 *
	 * Intended for the "Rescan tokens" UI affordance covering the edge case of a user
	 * installing new PKCS#11 middleware *while the app is running*: PC/SC events fire only
	 * for card / reader hardware changes, not for filesystem installs, so the cache would
	 * otherwise stay stale until app restart.  The wrapping [Pkcs11Discoverer.beginDiscovery]
	 * / [Pkcs11Discoverer.endDiscovery] keep [Pkcs11Discoverer.discoveryRunning] at `true`
	 * for the whole rescan window so passive cache readers see a single coherent in-flight
	 * cycle rather than the brief false gap between [Pkcs11Discoverer.invalidateCache] and
	 * the inner [Pkcs11Discoverer.discoverTokens] call.
	 *
	 * Fire-and-forget: returns immediately while the rescan runs on this service's own
	 * scope.  Consumers reactive to [Pkcs11Discoverer.discoveryRunning] (the sign-dialog
	 * inline indicator + auto-refresh) handle the visible feedback.
	 */
	fun rescan() {
		scope.launch {
			discoverer.beginDiscovery()
			try {
				logger.info { "Manual rescan: clearing caches and re-running discovery" }
				discoverer.invalidateCache()
				runRediscovery()
			} finally {
				discoverer.endDiscovery()
			}
		}
	}

	/**
	 * Re-run the full token-discovery cycle so [Pkcs11Discoverer]'s caches are populated
	 * with fresh hardware state.  The result is discarded; the populated cache is the
	 * useful side effect.
	 *
	 * Re-reads user libraries on every call because the user may edit
	 * [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries] mid-session
	 * via Global Settings.  Failures are logged but never propagated — a failed background
	 * cycle simply leaves the cache empty, and the next dialog open falls back to the
	 * normal lazy-probe path.
	 */
	private suspend fun runRediscovery() {
		try {
			val config = configRepository.getCurrentConfig()
			val userLibs = config.global.customPkcs11Libraries.map { it.name to it.path }
			val tokens = discoverer.discoverTokens(
				appDataPkcs11Dir = appDataPkcs11Dir,
				userPkcs11Libraries = userLibs,
			)
			logger.info {
				"Background rediscovery completed — ${tokens.size} PKCS#11 token(s) cached: " +
					tokens.map { it.name }
			}
		} catch (e: Throwable) {
			logger.warn(e) { "Background rediscovery after PC/SC event failed — next dialog open will probe lazily" }
		}
	}

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
