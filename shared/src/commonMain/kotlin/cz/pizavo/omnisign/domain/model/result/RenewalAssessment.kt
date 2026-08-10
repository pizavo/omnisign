package cz.pizavo.omnisign.domain.model.result

import kotlin.time.Instant

/**
 * The outcome of assessing one document for archival renewal: what has to happen, why, and by when.
 *
 * The reason travels with the verdict because the caller cannot infer it. "Needs renewal" means
 * different work depending on what the document is missing — embedding revocation data, refreshing
 * it, sealing it, or re-timestamping — and a batch run has to log and report which of those it did.
 *
 * @property need What, if anything, the document calls for.
 * @property reason Why, when [need] is [RenewalNeed.NEEDED] or [RenewalNeed.UNRECOVERABLE]. `null`
 *   for the outcomes that call for nothing.
 * @property dueAt The deadline that drove the verdict: the instant by which the step has to happen
 *   while it still can. In the past for [RenewalNeed.UNRECOVERABLE] — that is what makes it
 *   unrecoverable. `null` when no deadline applies or none could be determined.
 */
data class RenewalAssessment(
    val need: RenewalNeed,
    val reason: RenewalReason? = null,
    val dueAt: Instant? = null,
) {
    companion object {
        /** The document needs no action; nothing is due. */
        fun notNeeded(): RenewalAssessment = RenewalAssessment(RenewalNeed.NOT_NEEDED)

        /** The document needs the step implied by [reason], due at [dueAt]. */
        fun needed(reason: RenewalReason, dueAt: Instant? = null): RenewalAssessment =
            RenewalAssessment(RenewalNeed.NEEDED, reason, dueAt)

        /**
         * The step implied by [reason] can no longer be performed, its deadline [dueAt] having
         * passed. Reported once rather than retried, since no later attempt can succeed.
         */
        fun unrecoverable(reason: RenewalReason, dueAt: Instant? = null): RenewalAssessment =
            RenewalAssessment(RenewalNeed.UNRECOVERABLE, reason, dueAt)

        /** The document carries no signature for signature-scoped renewal to extend. */
        fun noSignature(): RenewalAssessment = RenewalAssessment(RenewalNeed.NO_SIGNATURE)
    }
}
