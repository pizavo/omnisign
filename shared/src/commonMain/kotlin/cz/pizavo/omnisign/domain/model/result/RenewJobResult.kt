package cz.pizavo.omnisign.domain.model.result

/**
 * Outcome of a single renewal job execution.
 *
 * @property name The renewal job name.
 * @property files Per-file status entries. Empty when no files matched the job's globs.
 * @property renewed Number of files successfully re-timestamped in this job.
 * @property errors Number of errors in this job — one per file that failed to renew, or one for a
 *   configuration error that stopped the job before any file was processed.
 * @property unrecoverable Number of files whose preservation deadline has already passed. Counted
 *   apart from [errors] on purpose: nothing failed, and no later run can succeed either, so they
 *   must not make every future run report itself as failing.
 * @property newlyUnrecoverable How many of [unrecoverable] were not already in that state at the
 *   previous run. Only these warrant a notification — the rest have been reported before and cannot
 *   change.
 * @property notify Whether the job requested OS notifications on completion.
 */
data class RenewJobResult(
    val name: String,
    val files: List<RenewFileStatus> = emptyList(),
    val renewed: Int = 0,
    val errors: Int = 0,
    val unrecoverable: Int = 0,
    val newlyUnrecoverable: Int = 0,
    val notify: Boolean = false,
)

