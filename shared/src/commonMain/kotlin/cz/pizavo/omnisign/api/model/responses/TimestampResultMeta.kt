package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a timestamped/extended PDF binary.
 *
 * Carried in the `X-OmniSign-Result` header of the `POST /api/v1/timestamp` response; the
 * extended PDF itself is the response body. Lives in `shared` so both the server (which
 * encodes it) and the web client (which decodes it) reference one definition.
 *
 * @property newLevel The PAdES level after timestamping/extension.
 * @property annotatedWarnings Warnings enriched with the DSS identifiers of the affected
 *   certificates or timestamps. Clients can render the [AnnotatedWarning.summary] as
 *   the headline text and use the per-warning identifier set to surface tooltips or "show
 *   affected entity" affordances. A flat summary list can be derived client-side as
 *   `annotatedWarnings.map { it.summary }`.
 */
@Serializable
data class TimestampResultMeta(
	val newLevel: String,
	val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
)
