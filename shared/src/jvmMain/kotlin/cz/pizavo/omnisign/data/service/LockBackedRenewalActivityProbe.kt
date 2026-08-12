package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
import cz.pizavo.omnisign.domain.port.RenewalLock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [RenewalActivityProbe] backed by the host-wide [RenewalLock] that a run holds for its whole
 * duration.
 *
 * The test is to acquire the lock and release it again immediately, which succeeds exactly when no
 * run is executing. Using the run's own lock means the answer cannot drift from reality, as a
 * heartbeat file or an elapsed-time heuristic could.
 *
 * The acquisition is momentary but real, so a run starting in that same instant would find the lock
 * taken and skip itself. That is already an ordinary `alreadyRunning` outcome, which still evaluates
 * staleness, and the window is smaller than the one two OmniSign processes already race for.
 *
 * @param renewalLock The lock a renewal run holds while it executes.
 */
class LockBackedRenewalActivityProbe(private val renewalLock: RenewalLock) : RenewalActivityProbe {

	override fun isRunInFlight(): Boolean =
		try {
			val handle = renewalLock.tryAcquire()
			if (handle == null) {
				true
			} else {
				handle.close()
				false
			}
		} catch (e: Exception) {
			logger.debug(e) { "Could not probe the renewal lock; reporting no run in flight" }
			false
		}
}
