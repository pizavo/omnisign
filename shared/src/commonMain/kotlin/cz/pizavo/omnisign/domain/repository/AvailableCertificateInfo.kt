package cz.pizavo.omnisign.domain.repository

import kotlin.time.Instant

/**
 * Information about an available certificate from a token, used for listing and selection.
 *
 * [keyUsages] contains the X.509 key usage extension values such as `"digitalSignature"`
 * or `"nonRepudiation"`. The list is empty when the token does not expose usage bits.
 *
 * Not to be confused with [cz.pizavo.omnisign.domain.model.signature.CertificateInfo],
 * which holds certificate details extracted during validation.
 */
data class AvailableCertificateInfo(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val validFrom: Instant,
    val validTo: Instant,
    val tokenType: String,
    val keyUsages: List<String> = emptyList()
)
