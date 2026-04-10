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
 * @property isQualified Whether the certificate contains the QcCompliance statement
 *   (`id-etsi-qcs-QcCompliance`, OID `0.4.0.1862.1.1`), indicating it is a qualified
 *   certificate under eIDAS.  `null` when the QCStatements extension is absent or unreadable.
 * @property isQscd Whether the certificate contains the QcSSCD statement
 *   (`id-etsi-qcs-QcSSCD`, OID `0.4.0.1862.1.4`), indicating the private key is protected
 *   by a Qualified Signature/Seal Creation Device.  `null` when the QCStatements extension
 *   is absent or unreadable.
 */
data class CertificateEntry(
    val alias: String,
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: Instant,
    val validTo: Instant,
    val keyUsages: List<String>,
    val tokenInfo: TokenInfo,
    val isQualified: Boolean? = null,
    val isQscd: Boolean? = null,
)
