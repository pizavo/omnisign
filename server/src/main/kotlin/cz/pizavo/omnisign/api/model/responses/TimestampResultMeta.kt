package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a timestamped/extended PDF binary.
 *
 * @property newLevel The PAdES level after timestamping/extension.
 * @property annotatedWarnings Warnings enriched with the DSS identifiers of the affected
 *   certificates or timestamps. Clients can render the [AnnotatedWarningResponse.summary] as
 *   the headline text and use the per-warning identifier set to surface tooltips or "show
 *   affected entity" affordances. A flat summary list can be derived client-side as
 *   `annotatedWarnings.map { it.summary }`.
 */
@Serializable
data class TimestampResultMeta(
	val newLevel: String,
	val annotatedWarnings: List<AnnotatedWarningResponse> = emptyList(),
)

