package cz.pizavo.omnisign.domain.model.result

/**
 * Status of a single file processed during a renewal batch run.
 *
 * @property path Absolute path to the file.
 * @property status Outcome category.
 * @property message Optional human-readable error or informational message.
 * @property warnings User-friendly warning summaries emitted during renewal.
 * @property reason The preservation gap this outcome relates to, when one applies: what the file
 *   needed, was promoted for, was skipped over, or can no longer have done.
 */
data class RenewFileStatus(
    val path: String,
    val status: Status,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
    val reason: RenewalReason? = null,
) {

    /**
     * Possible outcomes for a file in a renewal run.
     */
    enum class Status {
        /**
         * The file was extended: re-timestamped, promoted to a level it had not reached, or
         * refreshed. [RenewFileStatus.reason] says which.
         */
        RENEWED,

        /** Nothing was due for the file — no action taken. */
        SKIPPED,

        /**
         * The file needed a step the job is configured not to perform, so it was left alone
         * deliberately. Distinct from [SKIPPED], which means nothing was due at all: here something
         * *is* due and the job declined it.
         */
        SKIPPED_BY_POLICY,

        /**
         * The step the file needs can no longer be performed — its deadline has passed. Not an
         * error: nothing failed and no later run can succeed either, so it is reported rather than
         * retried.
         */
        UNRECOVERABLE,

        /** Dry-run mode — the file would have been extended. */
        DRY_RUN,

        /** An error occurred while checking or extending the file. */
        ERROR,

        /** The job's configuration could not be resolved. */
        CONFIG_ERROR,
    }
}

