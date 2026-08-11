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
 *   while it still can. `null` when no deadline applies or none could be determined.
 * @property deadlineIsFinal Whether passing [dueAt] closes the window **for good**. Only the signing
 *   certificate's expiry does that, because revocation data for it stops being obtainable at that
 *   point and nothing brings it back. Every other deadline here is a horizon rather than a wall: a
 *   revocation response's `nextUpdate` lies in the past for any document that has been sitting for a
 *   while, and simply means newer data should be fetched. A caller deciding whether a failure is
 *   permanent must consult this and never infer it from [dueAt] being in the past.
 */
data class RenewalAssessment(
    val need: RenewalNeed,
    val reason: RenewalReason? = null,
    val dueAt: Instant? = null,
    val deadlineIsFinal: Boolean = false,
) {
    companion object {
        /** The document needs no action; nothing is due. */
        fun notNeeded(): RenewalAssessment = RenewalAssessment(RenewalNeed.NOT_NEEDED)

        /**
         * The document needs the step implied by [reason], due at [dueAt].
         *
         * @param deadlineIsFinal `true` only when [dueAt] is the signing certificate's expiry, after
         *   which the step becomes impossible rather than merely overdue.
         */
        fun needed(
            reason: RenewalReason,
            dueAt: Instant? = null,
            deadlineIsFinal: Boolean = false,
        ): RenewalAssessment = RenewalAssessment(RenewalNeed.NEEDED, reason, dueAt, deadlineIsFinal)

        /**
         * The step implied by [reason] can no longer be performed, its deadline [dueAt] having
         * passed. Reported once rather than retried, since no later attempt can succeed.
         */
        fun unrecoverable(reason: RenewalReason, dueAt: Instant? = null): RenewalAssessment =
            RenewalAssessment(RenewalNeed.UNRECOVERABLE, reason, dueAt, deadlineIsFinal = true)

        /** The document carries no signature for signature-scoped renewal to extend. */
        fun noSignature(): RenewalAssessment = RenewalAssessment(RenewalNeed.NO_SIGNATURE)
    }
}
