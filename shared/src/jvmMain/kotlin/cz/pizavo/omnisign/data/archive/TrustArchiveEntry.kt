package cz.pizavo.omnisign.data.archive

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import kotlinx.serialization.Serializable

/**
 * One trusted-certificate reference inside a [TrustArchiveManifest]: which certificate (by
 * [fingerprint]) a [scope] trusts and for which [type].
 *
 * The certificate bytes live in the archive's `trusted-certs/<fingerprint>.der` entry; this record
 * carries only the scope membership and the per-reference trust role.
 *
 * @property scope Scope key — `"global"` or `"profile:<name>"`.
 * @property fingerprint Algorithm-prefixed SHA-256 fingerprint naming the sibling DER entry.
 * @property type Trust role this reference grants in [scope].
 */
@Serializable
internal data class TrustArchiveEntry(
	val scope: String,
	val fingerprint: String,
	val type: TrustedCertificateType,
)
