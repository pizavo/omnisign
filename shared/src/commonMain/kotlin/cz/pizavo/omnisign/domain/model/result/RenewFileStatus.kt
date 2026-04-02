package cz.pizavo.omnisign.domain.model.result

/**
 * Status of a single file processed during a renewal batch run.
 *
 * @property path Absolute path to the file.
 * @property status Outcome category.
 * @property message Optional human-readable error or informational message.
 * @property warnings User-friendly warning summaries emitted during renewal.
 */
data class RenewFileStatus(
    val path: String,
    val status: Status,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
) {

    /**
     * Possible outcomes for a file in a renewal run.
     */
    enum class Status {
        /** File was successfully re-timestamped. */
        RENEWED,

        /** File's timestamp is still valid — no action taken. */
        SKIPPED,

        /** Dry-run mode — the file would have been re-timestamped. */
        DRY_RUN,

        /** An error occurred while checking or extending the file. */
        ERROR,

        /** The job's configuration could not be resolved. */
        CONFIG_ERROR,
    }
}

