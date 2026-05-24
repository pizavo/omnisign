package cz.pizavo.omnisign.data.trust

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import kotlinx.serialization.Serializable

/**
 * A reference from a scope to a stored certificate, carrying the per-reference trust role.
 *
 * @property fingerprint Algorithm-prefixed SHA-256 fingerprint of the referenced certificate.
 * @property type Trust role granted to the certificate within the owning scope.
 */
@Serializable
internal data class CertRef(
	val fingerprint: String,
	val type: TrustedCertificateType,
)
