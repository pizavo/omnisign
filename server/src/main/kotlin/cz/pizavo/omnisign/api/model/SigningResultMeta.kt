package cz.pizavo.omnisign.api.model

import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a signed or extended PDF binary.
 *
 * The actual PDF is streamed as the response body with `application/pdf`;
 * this DTO is included in a `X-OmniSign-Result` response header as JSON.
 *
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel PAdES level used.
 * @property warnings Human-readable warning summaries.
 */
@Serializable
data class SigningResultMeta(
	val signatureId: String,
	val signatureLevel: String,
	val warnings: List<String> = emptyList(),
)

