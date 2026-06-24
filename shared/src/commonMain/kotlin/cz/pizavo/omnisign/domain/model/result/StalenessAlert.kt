package cz.pizavo.omnisign.domain.model.result

/**
 * Signals that renewal has gone too long without a successful run, so the caller can raise a single
 * standing "renewal has stalled" notification on top of any per-run outcome.
 *
 * Produced only when a scheduled run actually executes — a completed run, or one skipped because the
 * lock was held — never at start-up, so a machine that was merely powered off is not mistaken for a
 * stalled renewal (and a successful run resets it). Staleness is wall-clock time since
 * [RenewalRunRecord.lastSuccessAt], so time the machine was off counts toward it — by design, since
 * the renewal buffer expires in real time and a prompt warning leaves the most time to react.
 *
 * @property daysWithoutSuccess Whole days since renewal last succeeded, for the notification body.
 */
data class StalenessAlert(
    val daysWithoutSuccess: Int,
)
