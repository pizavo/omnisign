package cz.pizavo.omnisign.domain.model.result

/**
 * Why a document needs a preservation step, and therefore which step it needs.
 *
 * Preservation is not one action on one clock. Each level is missing something different, each
 * gap closes by a different deadline, and only one of the four is the "wait until it is nearly
 * expiring" case that archival re-timestamping is usually described as:
 *
 * - [BELOW_LT] and [LT_NOT_SEALED] are *overdue by default* — the material they need is available
 *   now and will not be later, so waiting only shrinks the window.
 * - [LT_REFRESH_NEEDED] needs newer revocation data before anything is sealed over it.
 * - [TIMESTAMP_EXPIRING] and [ALGORITHM_WEAKENING] are the recoverable ones: the protection is
 *   complete and merely aging, and renewing it is possible at any time.
 */
enum class RenewalReason {

    /**
     * The document carries no usable long-term validation material (it is at B-B or B-T), so
     * revocation data has to be embedded.
     *
     * The hard deadline is the **signing certificate's** expiry, not any timestamp's: once that
     * certificate expires, its issuer stops vouching for its revocation status and no acceptable
     * revocation data can be obtained for it again. Missing this deadline is irreversible, which is
     * why a document in this state is reported as needing renewal immediately rather than when some
     * timestamp approaches expiry.
     */
    BELOW_LT,

    /**
     * The document is at B-LT, but its embedded revocation data predates the signature timestamp, so
     * none of it covers the moment of signing.
     *
     * Sealing this with an archival timestamp would freeze a gap rather than close it. The document
     * needs newer revocation data first; the issuer's `nextUpdate` is when that data is due.
     */
    LT_REFRESH_NEEDED,

    /**
     * The document is at B-LT with revocation data that does cover the signature, but nothing has a
     * proof of existence over that data yet.
     *
     * Embedded revocation data is a snapshot, not a proof: once its `nextUpdate` passes, or the
     * responder certificate that signed it expires, it can no longer be validated on its own. An
     * archival timestamp anchors it while it is still verifiable, so the deadline is the earlier of
     * those two.
     */
    LT_NOT_SEALED,

    /** The signing certificate of an uncovered timestamp is approaching expiry. */
    TIMESTAMP_EXPIRING,

    /**
     * A cryptographic algorithm protecting an uncovered timestamp is no longer acceptable, or is
     * expiring, under the configured cryptographic suite.
     */
    ALGORITHM_WEAKENING,
}
