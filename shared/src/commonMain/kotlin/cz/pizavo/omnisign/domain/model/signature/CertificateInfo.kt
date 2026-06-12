package cz.pizavo.omnisign.domain.model.signature

import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
 * @property chain The signing certificate's full certificate chain, each entry parsed into a
 *   complete dump of every field and extension for the full-certificate view. Ordered leaf-first
 *   (the signing certificate, whose details mirror the summary fields above) up to the trust anchor
 *   last. Populated on the JVM from each certificate's DER bytes; empty when those bytes were not
 *   available (e.g. older data, or a target that did not parse them).
 */
@Serializable
data class CertificateInfo(
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: Instant,
    val validTo: Instant,
    val keyUsages: List<String> = emptyList(),
    val isQualified: Boolean = false,
    val publicKeyAlgorithm: String? = null,
    val sha256Fingerprint: String? = null,
    val chain: List<CertificateChainLink> = emptyList(),
)
