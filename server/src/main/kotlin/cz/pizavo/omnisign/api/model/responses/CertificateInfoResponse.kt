package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import kotlinx.serialization.Serializable

/**
 * Serializable representation of [AvailableCertificateInfo] for API responses.
 *
 * [validFrom] and [validTo] are ISO-8601 strings derived from [kotlin.time.Instant.toString].
 *
 * @property alias Certificate alias used to reference it in signing requests.
 * @property subjectDN X.500 subject distinguished name.
 * @property issuerDN X.500 issuer distinguished name.
 * @property validFrom Certificate validity start as an ISO-8601 string.
 * @property validTo Certificate validity end as an ISO-8601 string.
 * @property tokenType Token type name (e.g. `PKCS11`, `PKCS12`, `MSCAPI`).
 * @property keyUsages X.509 key usage extension values present on the certificate.
 */
@Serializable
data class CertificateInfoResponse(
	val alias: String,
	val subjectDN: String,
	val issuerDN: String,
	val validFrom: String,
	val validTo: String,
	val tokenType: String,
	val keyUsages: List<String>,
)

/**
 * Map an [AvailableCertificateInfo] to a [CertificateInfoResponse].
 */
fun AvailableCertificateInfo.toResponse() = CertificateInfoResponse(
	alias = alias,
	subjectDN = subjectDN,
	issuerDN = issuerDN,
	validFrom = validFrom.toString(),
	validTo = validTo.toString(),
	tokenType = tokenType,
	keyUsages = keyUsages,
)

