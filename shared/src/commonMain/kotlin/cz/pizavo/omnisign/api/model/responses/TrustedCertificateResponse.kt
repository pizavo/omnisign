package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Sanitized API representation of a [TrustedCertificate] as it appears in one trust scope.
 *
 * Exposes the certificate's display identity and validity plus the per-reference trust [type], so a
 * remote (web) client can render a scope's trusted certificates without access to the server's
 * app-managed trust store. A trusted certificate is public material, so nothing is stripped.
 *
 * @property fingerprint Algorithm-prefixed SHA-256 fingerprint (`sha256-<hex>`); the stable identity.
 * @property subjectDN The certificate subject distinguished name.
 * @property notBefore Start of the certificate validity period.
 * @property notAfter End of the certificate validity period.
 * @property type The trust role this reference grants in the scope (CA, TSA, or ANY).
 */
@Serializable
data class TrustedCertificateResponse(
	val fingerprint: String,
	val subjectDN: String,
	val notBefore: Instant,
	val notAfter: Instant,
	val type: TrustedCertificateType,
)

/**
 * Map a [TrustedCertificate] read model to its sanitized [TrustedCertificateResponse].
 */
fun TrustedCertificate.toResponse() = TrustedCertificateResponse(
	fingerprint = fingerprint,
	subjectDN = subjectDN,
	notBefore = notBefore,
	notAfter = notAfter,
	type = type,
)

/**
 * Reverse-map a [TrustedCertificateResponse] back into a [TrustedCertificate] read model, for
 * read-only consumption by the web client.
 */
fun TrustedCertificateResponse.toCertificate() = TrustedCertificate(
	fingerprint = fingerprint,
	subjectDN = subjectDN,
	notBefore = notBefore,
	notAfter = notAfter,
	type = type,
)
