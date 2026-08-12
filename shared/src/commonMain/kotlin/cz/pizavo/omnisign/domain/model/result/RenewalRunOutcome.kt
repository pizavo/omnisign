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

    /**
     * The run began but never reported a result, its process having died first — a system restart,
     * power loss, or a kill.
     *
     * Recorded by the *following* run. A killed process executes no further code, so the next run is
     * the first thing able to observe that the previous one died. Without this the status would keep
     * showing the last successful run, and a machine interrupted every night would look healthy
     * indefinitely.
     */
    INTERRUPTED,
}

/**
 * A lower-case, human-readable label for [RenewalRunOutcome], for display in the CLI and desktop UI.
 */
val RenewalRunOutcome.label: String
    get() = when (this) {
        RenewalRunOutcome.SUCCESS -> "success"
        RenewalRunOutcome.COMPLETED_WITH_ERRORS -> "completed with errors"
        RenewalRunOutcome.FAILED -> "failed"
        RenewalRunOutcome.INTERRUPTED -> "interrupted"
    }
