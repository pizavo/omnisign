package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a signed or extended PDF binary.
 *
 * The actual PDF is streamed as the response body with `application/pdf`;
 * this DTO is included in a `X-OmniSign-Result` response header as JSON.
 *
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel PAdES level used.
 * @property annotatedWarnings Warnings enriched with the DSS identifiers of the affected
 *   certificates or timestamps. Clients can render the [AnnotatedWarningResponse.summary] as
 *   the headline text and use the per-warning identifier set to surface tooltips or "show
 *   affected entity" affordances. A flat summary list can be derived client-side as
 *   `annotatedWarnings.map { it.summary }`.
 * @property hasRevocationWarnings Whether any warnings relate to missing or failed revocation data.
 *   Mirrors [cz.pizavo.omnisign.domain.model.result.SigningResult.hasRevocationWarnings] so a remote
 *   client can decide whether to surface a "continue anyway / abort" prompt for ≥ B-LT levels
 *   without parsing warning strings.
 */
@Serializable
data class SigningResultMeta(
	val signatureId: String,
	val signatureLevel: String,
	val annotatedWarnings: List<AnnotatedWarningResponse> = emptyList(),
	val hasRevocationWarnings: Boolean = false,
)

