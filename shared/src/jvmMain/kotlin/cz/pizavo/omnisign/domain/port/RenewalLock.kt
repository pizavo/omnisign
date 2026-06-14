package cz.pizavo.omnisign.domain.port

/**
 * Host-wide mutual exclusion for archival-renewal runs.
 *
 * Guarantees that only one renewal execution re-timestamps documents at a time on a machine, so a
 * scheduled run overlapping a manual one — or a slow run still going when the next daily trigger
 * fires — cannot read, extend, and write the same file concurrently and lose data.
 *
 * On JVM the implementation is [cz.pizavo.omnisign.data.service.FileRenewalLock]; platforms that
 * never run batch renewal may leave this port unregistered.
 */
interface RenewalLock {

	/**
	 * Try to acquire the exclusive renewal lock without blocking.
	 *
	 * Throws if the lock cannot be established at all (for example its file cannot be created or
	 * the platform refuses the lock); the caller must then abort the run rather than proceed
	 * without the lock's protection.
	 *
	 * @return an [AutoCloseable] that releases the lock when closed, or `null` if another run
	 *   currently holds it — in which case the caller should skip rather than wait.
	 */
	fun tryAcquire(): AutoCloseable?
}
