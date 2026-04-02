package cz.pizavo.omnisign.domain.model.result

/**
 * Outcome of a single renewal job execution.
 *
 * @property name The renewal job name.
 * @property files Per-file status entries. Empty when no files matched the job's globs.
 * @property renewed Number of files successfully re-timestamped in this job.
 * @property errors Number of files that encountered errors in this job.
 * @property notify Whether the job requested OS notifications on completion.
 */
data class RenewJobResult(
    val name: String,
    val files: List<RenewFileStatus> = emptyList(),
    val renewed: Int = 0,
    val errors: Int = 0,
    val notify: Boolean = false,
)

