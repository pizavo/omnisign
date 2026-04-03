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
 */
data class RenewBatchResult(
    val checked: Int = 0,
    val renewed: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val dryRun: Boolean = false,
    val jobs: List<RenewJobResult> = emptyList(),
) {

    /**
     * `true` when the batch completed without any errors.
     */
    val success: Boolean get() = errors == 0
}

