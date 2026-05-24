package cz.pizavo.omnisign.domain.model.trust

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import kotlin.time.Instant

/**
 * A directly-trusted certificate as it appears in one [TrustScope] - the read model returned by
 * [cz.pizavo.omnisign.domain.repository.TrustStore.list].
 *
 * It joins the certificate's intrinsic metadata (identity and validity) with the per-reference
 * [type] the scope assigns. Display labels (CN, organization) are derived from [subjectDN].
 *
 * @property fingerprint The certificate's algorithm-prefixed SHA-256 fingerprint (`sha256-<hex>`);
 *   the stable identity and the handle used to remove it.
 * @property subjectDN The certificate subject distinguished name.
 * @property notBefore Start of the certificate validity period.
 * @property notAfter End of the certificate validity period.
 * @property type The trust role this reference grants (CA, TSA, or ANY).
 */
data class TrustedCertificate(
	val fingerprint: String,
	val subjectDN: String,
	val notBefore: Instant,
	val notAfter: Instant,
	val type: TrustedCertificateType,
)
