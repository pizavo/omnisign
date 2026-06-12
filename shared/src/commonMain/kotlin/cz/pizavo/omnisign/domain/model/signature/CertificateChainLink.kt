package cz.pizavo.omnisign.domain.model.signature

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import kotlinx.serialization.Serializable

/**
 * One certificate in the signing certificate's chain, parsed for the full-certificate view.
 *
 * Ordering within [CertificateInfo.chain] follows DSS: the signing (leaf) certificate first, then
 * each issuer above it, up to the trust anchor last. The certificate-details dialog presents the
 * chain in the reverse, top-down reading order (trust anchor at the top, signing certificate at the
 * bottom) and derives each entry's role from its position.
 *
 * @property commonName Subject common name, used as the concise row label, or `null` when the
 *   subject carries no CN — then [subjectDN] is the only human-readable identifier.
 * @property subjectDN Full subject distinguished name; the label fallback and disambiguator.
 * @property selfSigned Whether subject and issuer match, i.e. the certificate is a self-issued
 *   (root). Lets the dialog distinguish a true "Root CA" from a non-self-signed top-of-chain.
 * @property trustedVia The trust sources that vouch for this certificate under the environment the
 *   validation ran in (a snapshot): trusted list(s), the global store, and/or the active profile's
 *   store. Empty when the certificate is not trusted in that environment. The trusted certificate
 *   may be anywhere in the chain, not only the topmost one, and may have several sources at once.
 * @property details Complete parsed dump of every field and extension, grouped into
 *   [CertificateDetailSection]s, exactly as rendered for the leaf certificate.
 * @property der The certificate's raw DER bytes, so the full-certificate dialog can export it.
 */
@Serializable
data class CertificateChainLink(
    val commonName: String?,
    val subjectDN: String,
    val selfSigned: Boolean,
    val trustedVia: List<CertificateTrustSource>,
    val details: List<CertificateDetailSection>,
    val der: ByteArray,
) {
    /**
     * Structural equality with array-aware comparison of [der]. The compiler-generated data-class
     * `equals` would compare [der] by reference, so two links describing the very same certificate
     * would test unequal — breaking deduplication and letting Compose recompose on identical chains.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertificateChainLink) return false
        return commonName == other.commonName &&
            subjectDN == other.subjectDN &&
            selfSigned == other.selfSigned &&
            trustedVia == other.trustedVia &&
            details == other.details &&
            der.contentEquals(other.der)
    }

    /**
     * Hash consistent with [equals]: [der] contributes via [ByteArray.contentHashCode] so equal
     * certificates share a hash code.
     */
    override fun hashCode(): Int {
        var result = commonName?.hashCode() ?: 0
        result = 31 * result + subjectDN.hashCode()
        result = 31 * result + selfSigned.hashCode()
        result = 31 * result + trustedVia.hashCode()
        result = 31 * result + details.hashCode()
        result = 31 * result + der.contentHashCode()
        return result
    }
}

/**
 * This certificate's display role, derived from its position in the (leaf-first) chain and what the
 * chain anchors: the leaf is the signing or timestamp certificate, the topmost is a root (when
 * self-signed) or a non-self-signed certificate authority, and anything between is an intermediate
 * CA. Shared by the certificate-details dialog and the plain-text report so the labels never drift.
 *
 * @param isLeaf Whether this is the end-entity certificate (index 0 of the chain).
 * @param isTop Whether this is the topmost certificate (the last entry).
 * @param leafRole What the chain anchors — [TrustedCertificateType.TSA] labels the leaf a timestamp
 *   certificate; any other value labels it a signing certificate.
 */
fun CertificateChainLink.roleLabel(isLeaf: Boolean, isTop: Boolean, leafRole: TrustedCertificateType): String = when {
    isLeaf -> if (leafRole == TrustedCertificateType.TSA) "Timestamp certificate" else "Signing certificate"
    isTop -> if (selfSigned) "Root CA" else "Certificate Authority"
    else -> "Intermediate CA"
}
