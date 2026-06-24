package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persisted status of the most recent renewal batch run, surfaced by the CLI `schedule status`
 * command and the desktop scheduler section so a silently failing or stalled scheduler becomes
 * visible.
 *
 * Only real runs update the run-status fields: dry-runs and runs skipped because another run already
 * held the host-wide lock never change [outcome], the counts, or [lastRunAt]. A lock-skipped run may
 * still stamp [lastStaleNotifiedAt] when renewal has gone too long without a success, so a
 * persistently held lock is surfaced rather than silently ignored.
 *
 * @property lastRunAt When the most recent run finished.
 * @property outcome Outcome of the most recent run.
 * @property checked Files inspected in the most recent run.
 * @property renewed Files re-timestamped in the most recent run.
 * @property skipped Files whose timestamps were still valid in the most recent run.
 * @property errors Files that failed in the most recent run.
 * @property failureReason Why the run could not start, when [outcome] is [RenewalRunOutcome.FAILED]
 *   (e.g. the lock could not be acquired); `null` otherwise.
 * @property errorDetails File-scoped errors from the most recent run.
 * @property warnings Distinct user-friendly warning summaries emitted during the most recent run.
 * @property jobs Per-job rollup of the most recent run.
 * @property lastSuccessAt When the most recent *successful* run finished, carried forward across
 *   later failures; `null` if no run has ever succeeded.
 * @property failuresSinceSuccess Consecutive non-successful runs since [lastSuccessAt] (0 when the
 *   most recent run succeeded).
 * @property lastStaleNotifiedAt When the staleness notification was last raised — measured as
 *   wall-clock time since [lastSuccessAt] — so it re-fires at most once per
 *   [cz.pizavo.omnisign.domain.model.config.SchedulerConfig.stalenessThresholdDays] while renewal
 *   stays stale; `null` when none is outstanding (including after a success resets it).
 */
@Serializable
data class RenewalRunRecord(
    val lastRunAt: Instant,
    val outcome: RenewalRunOutcome,
    val checked: Int = 0,
    val renewed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val failureReason: String? = null,
    val errorDetails: List<RenewalRunError> = emptyList(),
    val warnings: List<String> = emptyList(),
    val jobs: List<RenewalRunJobSummary> = emptyList(),
    val lastSuccessAt: Instant? = null,
    val failuresSinceSuccess: Int = 0,
    val lastStaleNotifiedAt: Instant? = null,
)
