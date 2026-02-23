package cz.pizavo.omnisign.domain.model.signature

/**
 * Certificate information.
 *
 * @property subjectDN Distinguished name of the certificate subject.
 * @property issuerDN Distinguished name of the certificate issuer.
 * @property serialNumber Certificate serial number as a hex string.
 * @property validFrom Start of the certificate validity period.
 * @property validTo End of the certificate validity period.
 * @property keyUsages List of key usage extensions (e.g. "NON_REPUDIATION").
 * @property isQualified Whether the certificate is a qualified certificate under eIDAS.
 * @property publicKeyAlgorithm Algorithm of the public key (e.g. "RSA", "EC").
 * @property sha256Fingerprint SHA-256 fingerprint of the certificate in colon-separated hex notation.
 */
data class CertificateInfo(
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: String,
    val validTo: String,
    val keyUsages: List<String> = emptyList(),
    val isQualified: Boolean = false,
    val publicKeyAlgorithm: String? = null,
    val sha256Fingerprint: String? = null,
)

