package cz.pizavo.omnisign.domain.model.trust

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType

/**
 * A trust anchor resolved for a validation pass: the certificate's canonical DER bytes plus the
 * trust [type] that applies in the resolved scope.
 *
 * Returned by [cz.pizavo.omnisign.domain.repository.TrustStore.resolve]. Every anchor is loaded at
 * full trust; [type] drives the per-reference policy enforcement applied *after* DSS validation,
 * not the trust input.
 *
 * @property fingerprint The certificate's algorithm-prefixed SHA-256 fingerprint.
 * @property type The trust role assigned to this anchor in the resolved scope.
 * @property der The certificate's canonical DER encoding.
 */
class ResolvedTrustAnchor(
	val fingerprint: String,
	val type: TrustedCertificateType,
	val der: ByteArray,
)
