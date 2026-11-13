package cz.pizavo.omnisign.domain.model.validation

import kotlin.time.Instant

/**
 * The signing certificate's revocation status at the time a signature is judged against, its
 * best-signature-time, as [revocationOutcome] reads it from the dates its revocation checks give.
 */
sealed interface RevocationOutcome {
    /**
     * A check says the certificate had been revoked by then.
     */
    data object Revoked : RevocationOutcome

    /**
     * The certificate was still valid then and was revoked only later.
     *
     * @property on The earliest revocation date a check gives.
     */
    data class RevokedLater(val on: Instant) : RevocationOutcome

    /**
     * A check that says the certificate is good covers that time, being issued then or later or
     * still current then, and no check says it was revoked.
     */
    data object NotRevoked : RevocationOutcome

    /**
     * No check settles the status at that time: every good one had stopped being current before
     * it, or none gives a status.
     */
    data object Undetermined : RevocationOutcome
}
