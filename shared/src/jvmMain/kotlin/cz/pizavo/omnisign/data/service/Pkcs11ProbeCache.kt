package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-path cache of successful PKCS#11 probe results.
 *
 * Populated by [probeLibrary] on a successful out-of-process probe and by [primeCache]
 * when [Pkcs11WarmupService] has a fresh result to share.  Lookups in [probeLibrary]
 * short-circuit any present entry without spawning a subprocess — the hot path that makes
 * a second token-presence check inside the dialog-open flow effectively free.
 *
 * Entries live indefinitely; freshness is enforced by [Pkcs11CacheInvalidator] reacting to
 * PC/SC reader-state events (via [invalidateProbes]), not by a time-based TTL.  Failed or
 * empty probes are deliberately **not** cached so a card inserted mid-session is picked up
 * on the next call; the [Pkcs11CrashBlacklist] handles the "library always crashes" case
 * separately.
 *
 * Held as a single shared Koin singleton so warmup, the invalidator and the sign-dialog
 * read path all observe one cache instance (the load-bearing identity invariant).
 *
 * @property crashBlacklist Suppresses probing of a path that has crashed past the
 *   threshold within its sliding window; the record decays so a transient SafeNet-style
 *   crash does not disable a healthy library for the JVM lifetime.
 * @property prober Process-isolated probe strategy (default [Pkcs11SubprocessProber]) so a
 *   native crash (SIGSEGV) cannot take down the host JVM; injected for testability.
 */
class Pkcs11ProbeCache(
	private val crashBlacklist: Pkcs11CrashBlacklist = Pkcs11CrashBlacklist(),
	private val prober: Pkcs11Prober = Pkcs11SubprocessProber(),
) {

	private val probeCache = ConcurrentHashMap<String, List<Pkcs11TokenIdentity>>()

	/**
	 * Probe a single PKCS#11 library for token identities, using the cache when possible.
	 *
	 * Resolution order:
	 * 1. **Crash blacklist** — if the path has crashed enough times in the current sliding
	 *    window ([Pkcs11CrashBlacklist]), return empty without spawning anything.
	 * 2. **Cache hit** — if a successful probe result is present, return its identities
	 *    without spawning a subprocess.  Freshness is enforced by [Pkcs11CacheInvalidator]
	 *    reacting to PC/SC events.
	 * 3. **Cache miss** — probe out-of-process via [prober].  On non-empty success, populate
	 *    the cache.  On empty success or failure, leave the cache untouched so a later card
	 *    insertion is picked up on the next call.
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
	 * Used by [Pkcs11WarmupService] to share its successful warmup probes so the very first
	 * sign-dialog open does not re-spawn subprocesses for libraries warmup just validated.
	 * No-op when [identities] is empty — empty results are never cached, so a freshly
	 * inserted card is still picked up.
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
	 * libraries has not — typically a card insertion / removal observed via
	 * [PcscMonitorService.events].
	 */
	fun invalidateProbes() {
		probeCache.clear()
		logger.debug { "Probe cache cleared" }
	}

	/**
	 * Snapshot of the current cache contents, for the read-only `getCachedTokens` path.
	 *
	 * @return An immutable copy of `libraryPath -> identities`; never the live map.
	 */
	fun cachedProbes(): Map<String, List<Pkcs11TokenIdentity>> = probeCache.toMap()

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
