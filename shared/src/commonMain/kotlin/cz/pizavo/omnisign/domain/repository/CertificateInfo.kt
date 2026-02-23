package cz.pizavo.omnisign.domain.repository

/**
 * Information about an available certificate from a token.
 *
 * [keyUsages] contains the X.509 key usage extension values such as `"digitalSignature"`
 * or `"nonRepudiation"`. The list is empty when the token does not expose usage bits.
 */
data class CertificateInfo(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val validFrom: String,
    val validTo: String,
    val tokenType: String,
    val keyUsages: List<String> = emptyList()
)

