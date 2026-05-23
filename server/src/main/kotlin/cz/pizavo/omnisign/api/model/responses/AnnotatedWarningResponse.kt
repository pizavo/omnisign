package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import kotlinx.serialization.Serializable

/**
 * Serializable mirror of [AnnotatedWarning] for use inside API response payloads.
 *
 * The domain [AnnotatedWarning] is intentionally not annotated with `@Serializable` so that
 * `commonMain` result types stay free of transport concerns. This DTO keeps the full entity
 * attribution (DSS identifiers and any known human-readable names) so a remote client can
 * render tooltips and "show affected certificates/timestamps" affordances equivalent to the
 * desktop UI, instead of receiving only flat [AnnotatedWarning.summary] strings.
 *
 * @property summary Human-readable warning summary suitable for display.
 * @property affectedIds Full DSS identifiers of the certificates or timestamps the warning is about.
 *   Empty when the warning does not reference specific entities.
 * @property idNames Mapping from a DSS identifier in [affectedIds] to a human-readable name
 *   (e.g. certificate subject CN). Only populated when the metadata was available at capture time.
 */
@Serializable
data class AnnotatedWarningResponse(
	val summary: String,
	val affectedIds: List<String> = emptyList(),
	val idNames: Map<String, String> = emptyMap(),
)

/**
 * Map an [AnnotatedWarning] to its serializable [AnnotatedWarningResponse] mirror.
 */
fun AnnotatedWarning.toResponse() = AnnotatedWarningResponse(
	summary = summary,
	affectedIds = affectedIds,
	idNames = idNames,
)
