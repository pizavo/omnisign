package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * PAdES signature levels supported by the application.
 */
@Serializable
enum class SignatureLevel {
    /** Basic Electronic Signature — no timestamp. */
    PADES_BASELINE_B,

    /** Electronic Signature with Time — RFC 3161 document timestamp embedded. */
    PADES_BASELINE_T,

    /** Electronic Signature with Long-Term Validation Material — includes CRL/OCSP data. */
    PADES_BASELINE_LT,

    /** Electronic Signature for Long-Term Archival — includes an archival document timestamp. */
    PADES_BASELINE_LTA
}
