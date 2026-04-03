package cz.pizavo.omnisign.domain.service

import kotlin.time.Instant

/**
 * Certificate entry from a token.
 *
 * @property alias Certificate alias identifier.
 * @property subjectDN Distinguished name of the certificate subject.
 * @property issuerDN Distinguished name of the certificate issuer.
 * @property serialNumber Certificate serial number.
 * @property validFrom Start of the certificate validity period.
 * @property validTo End of the certificate validity period.
 * @property keyUsages List of key usage extension values.
 * @property tokenInfo Token from which this certificate was loaded.
 */
data class CertificateEntry(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: Instant,
    val validTo: Instant,
    val keyUsages: List<String>,
    val tokenInfo: TokenInfo
)
