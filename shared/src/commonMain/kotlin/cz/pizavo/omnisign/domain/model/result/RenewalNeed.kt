package cz.pizavo.omnisign.domain.model.result

/**
 * Outcome of an archival-renewal eligibility check for a single document.
 *
 * Distinguishes the two "no action" cases so the renewal batch can report them differently: a
 * document whose archival protection is simply still current, versus one that carries timestamps but
 * no signature for OmniSign's signature-scoped renewal to extend — the latter is an informational
 * skip, not a failure.
 */
enum class RenewalNeed {
    /** An uncovered timestamp is approaching certificate expiry (or its algorithms have aged) — re-timestamp. */
    NEEDED,

    /** No uncovered timestamp is near expiry — the document's archival protection is still current. */
    NOT_NEEDED,

    /**
     * The document has no signature, so it cannot be extended and OmniSign's signature-scoped renewal
     * does not apply (a standalone document timestamp from another tool). Reported as an informational
     * skip rather than an error.
     */
    NO_SIGNATURE,
}
