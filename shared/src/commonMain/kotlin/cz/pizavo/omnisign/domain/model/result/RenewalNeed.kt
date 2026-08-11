package cz.pizavo.omnisign.domain.model.result

/**
 * Outcome of an archival-renewal eligibility check for a single document.
 *
 * Distinguishes the cases that call for no action so the renewal batch can report them differently:
 * a document whose protection is still current, one that carries no signature for OmniSign's
 * signature-scoped renewal to extend, and one whose deadline has already passed. Only the last is a
 * problem, and none of them is a failure of the run.
 *
 * Paired with a [RenewalReason] in [RenewalAssessment], which says *which* step is called for.
 */
enum class RenewalNeed {
    /** The document needs a preservation step now; [RenewalAssessment.reason] says which. */
    NEEDED,

    /** Nothing is due yet — the document's protection is complete and not close to aging out. */
    NOT_NEEDED,

    /**
     * The document has no signature, so it cannot be extended and OmniSign's signature-scoped renewal
     * does not apply (a standalone document timestamp from another tool). Reported as an informational
     * skip rather than an error.
     */
    NO_SIGNATURE,

    /**
     * The step the document needs can no longer be performed: its deadline has passed.
     *
     * Reached when a document below B-LT is found after its signing certificate has expired — no
     * acceptable revocation data for that certificate can be obtained any more, so it can never
     * reach B-LT. Reported once and then left alone, because retrying every run would report a
     * failure nobody can act on and would keep the run from ever succeeding.
     */
    UNRECOVERABLE,
}
