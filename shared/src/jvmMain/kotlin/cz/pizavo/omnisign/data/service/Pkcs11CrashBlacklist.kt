package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Records PKCS#11 libraries that have crashed during subprocess validation so [Pkcs11Discoverer]
 * can skip the next probe attempt — but only for as long as the crashes look genuine.
 *
 * Earlier revisions used a permanent set: a single SIGSEGV during warmup blacklisted the
 * library for the rest of the JVM session.  Combined with SafeNet's documented instability
 * under contention (multiple subprocesses calling `C_Initialize` concurrently can fail one
 * at random), the practical effect was that the user's eToken would intermittently
 * disappear from the dialog and stay missing until the app was restarted.
 *
 * The current model is **count-thresholded with a sliding window**:
 *
 * - The first crash for a path starts a fresh record.
 * - Subsequent crashes within [crashWindow] increment the record's counter.
 * - A path is considered crashed only after [crashThreshold] crashes inside the window.
 * - Once the window elapses without further crashes the record decays to fresh state on
 *   the next read or write — a transient flake never poisons the lib forever.
 *
 * **Timeouts are intentionally not blacklisted** — see [Pkcs11WarmupService.warmupSingleLibrary].
 * A transient hang during warmup should not disable a healthy library; discovery
 * subprocess-probes on demand instead.
 *
 * Thread-safety: [ConcurrentHashMap.compute] is atomic, so concurrent calls to
 * [registerCrashed] and [isCrashed] cannot corrupt the per-path record.
 *
 * **Population is warmup-only.**  Only [Pkcs11WarmupService] calls [registerCrashed]; the
 * discovery / "Rescan tokens" path ([Pkcs11ProbeCache.probeLibrary]) only *reads*
 * [isCrashed], never records a crash.  Recovery is therefore by the decay window **alone**:
 * there is deliberately no manual force-clear wired into rescan, because the discovery path
 * never re-registers a crash, so force-clearing would let a genuinely broken library
 * re-crash its probe subprocess on every rescan with no re-suppression until the next
 * warmup.  The remedy for a persistently broken library is to repair the middleware, not
 * to bypass the window.
 *
 * @param crashWindow Sliding window over which crashes are counted.  Defaults to 5 minutes
 *   — long enough for warmup-vs-discovery contention to settle, short enough that a real
 *   re-test after a couple of dialog opens still sees a fresh record.
 * @param crashThreshold Number of crashes within the window required to suppress further
 *   probes.  Defaults to `3` so a single SIGSEGV — almost certainly a transient — does not
 *   silently disable the library.
 * @param clock Time source.  Defaults to [Clock.System]; tests inject a virtual clock to
 *   advance time deterministically.
 */
class Pkcs11CrashBlacklist(
	private val crashWindow: Duration = DEFAULT_CRASH_WINDOW,
	private val crashThreshold: Int = DEFAULT_CRASH_THRESHOLD,
	private val clock: Clock = Clock.System,
) {

	/**
	 * Per-path crash bookkeeping: when the current sliding window started and how many
	 * crashes have been observed inside it.
	 *
	 * @property windowStart Timestamp of the first crash that opened the current window.
	 * @property count Crashes recorded since [windowStart].
	 */
	private data class CrashRecord(val windowStart: Instant, val count: Int)

	private val records = ConcurrentHashMap<String, CrashRecord>()

	/**
	 * Record one crash for [libraryPath].
	 *
	 * Called by [Pkcs11WarmupService] (and any future caller that observes a non-zero exit
	 * code from the probe subprocess).  When the existing record is already older than
	 * [crashWindow], the record is reset and the count starts at 1; otherwise the count is
	 * incremented.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun registerCrashed(libraryPath: String) {
		val now = clock.now()
		val updated = records.compute(libraryPath) { _, prev ->
			if (prev == null || now - prev.windowStart > crashWindow) {
				CrashRecord(windowStart = now, count = 1)
			} else {
				prev.copy(count = prev.count + 1)
			}
		}
		logger.debug {
			"Crash recorded for '$libraryPath' " +
					"(count=${updated?.count}/${crashThreshold} within ${crashWindow.inWholeMinutes}m)"
		}
	}

	/**
	 * Whether [libraryPath] has crashed enough times in the current window to be skipped.
	 *
	 * Stale records (older than [crashWindow]) are pruned on read so the next probe attempt
	 * starts fresh — important for SafeNet-style middleware that crashes intermittently
	 * under contention but recovers cleanly once the contention dissipates.
	 *
	 * @param libraryPath Absolute path to the PKCS#11 shared library.
	 */
	fun isCrashed(libraryPath: String): Boolean {
		val record = records[libraryPath] ?: return false
		val now = clock.now()
		if (now - record.windowStart > crashWindow) {
			records.remove(libraryPath, record)
			return false
		}
		return record.count >= crashThreshold
	}

	private companion object {
		val logger = KotlinLogging.logger {}

		/**
		 * Default sliding window over which crashes are counted.
		 *
		 * 5 minutes covers the worst case where startup warmup contention plus the very
		 * first dialog open happen close together, while still allowing a calm later dialog
		 * open to see a fresh record.
		 */
		val DEFAULT_CRASH_WINDOW = 5.minutes

		/**
		 * Default number of crashes inside the window required to suppress further probes.
		 */
		const val DEFAULT_CRASH_THRESHOLD = 3
	}
}
