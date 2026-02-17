package cz.pizavo.omnisign.domain.service

/**
 * Certificate entry from a token.
 */
data class CertificateEntry(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: String,
    val validTo: String,
    val keyUsages: List<String>,
    val tokenInfo: TokenInfo
)

