package cz.pizavo.omnisign.domain.port

/**
 * Tells whether a renewal run is executing on this machine right now.
 *
 * Exists to disambiguate [cz.pizavo.omnisign.domain.model.result.RenewalRunRecord.runStartedAt], which
 * is set when a run begins and cleared when it finishes. Finding it set therefore means one of two
 * things: a run is still under way, or a run died without clearing it. Reporting the second as the
 * first hides a stalled scheduler, while reporting the first as the second raises a false alarm
 * whenever someone checks the status during the nightly batch.
 *
 * On JVM the implementation is [cz.pizavo.omnisign.data.service.LockBackedRenewalActivityProbe].
 * Platforms that never run batch renewal may leave this port unregistered, in which case callers
 * should present the marker without a verdict.
 */
interface RenewalActivityProbe {

    /**
     * Whether a renewal run currently holds the host-wide renewal lock.
     *
     * Never throws: a probe that cannot reach the lock reports `false`, a lock that cannot be
     * established being a condition the run itself already fails loudly on.
     *
     * @return `true` while a run is executing on this machine, `false` otherwise.
     */
    fun isRunInFlight(): Boolean
}
