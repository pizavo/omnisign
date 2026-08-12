package cz.pizavo.omnisign.domain.port

/**
 * Receives a renewal alert that has to be delivered *during* a run rather than from its result.
 *
 * Every other renewal notification is raised by the caller once
 * [cz.pizavo.omnisign.domain.model.result.RenewBatchResult] comes back. That path cannot carry the
 * one alert about runs being killed, because the same kill discards the result. The alert therefore
 * has to leave the use case at the moment it is established, which is the only reason this port
 * exists; it is deliberately narrow rather than a general notification channel.
 *
 * Platforms with no way to notify may leave it unregistered, in which case the condition is still
 * recorded and remains visible in the run status.
 */
interface RenewalAlertSink {

    /**
     * Report that renewal runs are being killed before they can finish, [consecutive] of them in a
     * row now.
     *
     * Raised once per streak, when the count first reaches the threshold. Repeating it every night
     * would say nothing new and would train the user to dismiss it; the standing signal is the run
     * status and the settings badge, which stay lit for as long as the problem lasts.
     *
     * @param consecutive How many runs in a row have now been killed before finishing.
     */
    fun runsKeepBeingInterrupted(consecutive: Int)
}
