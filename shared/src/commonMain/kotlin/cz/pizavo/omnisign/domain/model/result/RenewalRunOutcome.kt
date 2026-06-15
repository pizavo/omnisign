package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable

/**
 * Outcome of a complete renewal batch run, as persisted in the [RenewalRunRecord].
 */
@Serializable
enum class RenewalRunOutcome {

    /** Every inspected file was processed without error. */
    SUCCESS,

    /** The run completed, but one or more files failed. */
    COMPLETED_WITH_ERRORS,

    /** The run could not start — the host-wide renewal lock could not be acquired. */
    FAILED,
}

/**
 * A lower-case, human-readable label for [RenewalRunOutcome], for display in the CLI and desktop UI.
 */
val RenewalRunOutcome.label: String
    get() = when (this) {
        RenewalRunOutcome.SUCCESS -> "success"
        RenewalRunOutcome.COMPLETED_WITH_ERRORS -> "completed with errors"
        RenewalRunOutcome.FAILED -> "failed"
    }
