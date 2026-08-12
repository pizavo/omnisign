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
 * @property unrecoverable Files whose preservation deadline had already passed in the most recent
 *   run. Recorded apart from [errors] so they neither mark the run unsuccessful nor hold back
 *   [lastSuccessAt], which would leave the staleness alert firing for ever over something no run can
 *   fix.
 * @property failureReason Why the run could not start, when [outcome] is [RenewalRunOutcome.FAILED]
 *   (e.g. the lock could not be acquired); `null` otherwise.
 * @property unrecoverablePaths Files found past their preservation deadline in the most recent run.
 *   Carried forward so the next run can tell a newly terminal document from one it has already
 *   reported, and notify only about the former: a condition nobody can act on must be raised once,
 *   not every day for as long as the file sits in the job's globs.
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
 * @property runStartedAt When the run currently in flight began, or `null` when no run is under way.
 *   Written before the batch starts and cleared by the record it writes when it finishes, so a value
 *   that survives means that run never finished. This is the only way an interrupted run can be
 *   noticed at all, since a killed process cannot report its own death and every other failure channel
 *   — this record, the OS notification, the job log — is produced only after the batch returns.
 *   Telling "still running" from "died" additionally needs
 *   [cz.pizavo.omnisign.domain.port.RenewalActivityProbe].
 * @property consecutiveInterruptions How many runs in a row have been killed before finishing, reset
 *   by any run that completes. Counted apart from [failuresSinceSuccess], which a completed run that
 *   merely failed also increments: a machine shutting down mid-batch every night is a different
 *   problem from a document that keeps erroring, and only the first is invisible to every other
 *   channel.
 */
@Serializable
data class RenewalRunRecord(
    val lastRunAt: Instant,
    val outcome: RenewalRunOutcome,
    val checked: Int = 0,
    val renewed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val unrecoverable: Int = 0,
    val unrecoverablePaths: List<String> = emptyList(),
    val failureReason: String? = null,
    val errorDetails: List<RenewalRunError> = emptyList(),
    val warnings: List<String> = emptyList(),
    val jobs: List<RenewalRunJobSummary> = emptyList(),
    val lastSuccessAt: Instant? = null,
    val failuresSinceSuccess: Int = 0,
    val lastStaleNotifiedAt: Instant? = null,
    val runStartedAt: Instant? = null,
    val consecutiveInterruptions: Int = 0,
)
