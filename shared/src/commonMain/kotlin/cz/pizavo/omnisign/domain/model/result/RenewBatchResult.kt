package cz.pizavo.omnisign.domain.model.result

/**
 * Aggregated result of a complete renewal batch run across all jobs.
 *
 * @property checked Total number of files inspected.
 * @property renewed Total number of files successfully re-timestamped (or would be, during dry-run).
 * @property skipped Total number of files whose timestamps are still valid.
 * @property errors Total number of files (or jobs) that encountered errors.
 * @property dryRun Whether this was a dry-run (no files were modified).
 * @property jobs Per-job breakdown of file outcomes.
 * @property alreadyRunning `true` when the run was skipped because another renewal process held
 *   the host-wide lock; all other counts are then zero and no files were inspected.
 * @property lockError Non-null when the run did not start because the renewal lock could not be
 *   established (its file could not be created or locked); carries the failure reason.
 * @property stalenessAlert Non-null when renewal has gone too long without a successful run, so the
 *   caller should raise a standing staleness notification on top of any per-job outcome — set on a
 *   completed run or a run skipped because the lock was held; `null` for dry-runs, lock failures, and
 *   runs that are healthy or only recently failing.
 */
data class RenewBatchResult(
    val checked: Int = 0,
    val renewed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val dryRun: Boolean = false,
    val jobs: List<RenewJobResult> = emptyList(),
    val alreadyRunning: Boolean = false,
    val lockError: String? = null,
    val stalenessAlert: StalenessAlert? = null,
) {

    /**
     * `true` when the batch completed without any errors and the lock was acquired.
     */
    val success: Boolean get() = errors == 0 && lockError == null
}

