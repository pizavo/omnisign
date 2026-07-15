package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a signed or extended PDF binary.
 *
 * The actual PDF is streamed as the response body with `application/pdf`;
 * this DTO is included in a `X-OmniSign-Result` response header as JSON so
 * the web client can reconstruct the same [cz.pizavo.omnisign.domain.model.result.SigningResult]
 * shape its JVM counterpart receives.
 *
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel PAdES level used.
 * @property annotatedWarnings Warnings enriched with the DSS identifiers of the affected
 *   certificates or timestamps. Each [AnnotatedWarning.summary] is a localizable text a client
 *   renders in the active locale (falling back to English), and the per-warning identifier set
 *   backs tooltips or "show affected entity" affordances.
 * @property hasRevocationWarnings Whether any warnings relate to missing or failed
 *   revocation data. Mirrors
 *   [cz.pizavo.omnisign.domain.model.result.SigningResult.hasRevocationWarnings] so a
 *   remote client can decide whether to surface a "continue anyway / abort" prompt for
 *   ≥ B-LT levels without parsing warning strings.
 */
@Serializable
data class SigningResultMeta(
	val signatureId: String,
	val signatureLevel: String,
	val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
	val hasRevocationWarnings: Boolean = false,
)
