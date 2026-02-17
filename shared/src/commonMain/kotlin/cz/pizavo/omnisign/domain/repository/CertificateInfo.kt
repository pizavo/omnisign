package cz.pizavo.omnisign.domain.repository

/**
 * Information about an available certificate from a token.
 */
data class CertificateInfo(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val validFrom: String,
    val validTo: String,
    val tokenType: String
)

