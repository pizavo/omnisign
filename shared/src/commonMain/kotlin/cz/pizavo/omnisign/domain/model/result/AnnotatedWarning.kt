package cz.pizavo.omnisign.domain.model.result

/**
 * A warning message enriched with the identifiers of the affected entities.
 *
 * Carries both the human-readable [summary] text produced by the sanitizer and the
 * full DSS identifiers (e.g. `C-DA6DD49F6DAF…` for certificates, `T-FFFF…` for
 * timestamps) that were grouped into the warning. UI layers can use [affectedIds]
 * to let the user inspect the affected certificates or timestamps — for example by
 * opening a dialog with selectable, copyable text when the count mention in the
 * summary is clicked. When a human-readable name is known (extracted from the
 * certificate chain), it is stored in [idNames] keyed by the DSS identifier.
 *
 * @property summary Human-readable warning summary suitable for display.
 * @property affectedIds Full DSS identifiers of the affected certificates or timestamps.
 *   Empty when the warning does not reference specific entities.
 * @property idNames Mapping from DSS identifier to a human-readable name (e.g. subject
 *   CN or DN). Only populated for certificates whose metadata was available at the time
 *   the warning was captured.
 */
data class AnnotatedWarning(
	val summary: String,
	val affectedIds: List<String> = emptyList(),
	val idNames: Map<String, String> = emptyMap(),
)



