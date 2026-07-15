package cz.pizavo.omnisign.domain.model.result

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import kotlinx.serialization.Serializable

/**
 * A warning message enriched with the identifiers of the affected entities.
 *
 * Carries both the locale-independent [summary] text produced by the sanitizer and the
 * full DSS identifiers (e.g. `C-DA6DD49F6DAF…` for certificates, `T-FFFF…` for
 * timestamps) that were grouped into the warning. The [summary] is a [LocalizableText]:
 * a [LocalizableText.Keyed] message for recognized warning categories (which a frontend
 * renders in the active locale, and the CLI/server/logs render in English) or a
 * [LocalizableText.Literal] for an unmatched DSS message kept verbatim. Because the type
 * is serializable, a server can hand it to the web client and let the client translate.
 *
 * UI layers can use [affectedIds] to let the user inspect the affected certificates or
 * timestamps — for example by opening a dialog with selectable, copyable text when the
 * count mention in the summary is clicked. When a human-readable name is known (extracted
 * from the certificate chain), it is stored in [idNames] keyed by the DSS identifier.
 *
 * @property summary Locale-independent warning summary suitable for display.
 * @property affectedIds Full DSS identifiers of the affected certificates or timestamps.
 *   Empty when the warning does not reference specific entities.
 * @property idNames Mapping from DSS identifier to a human-readable name (e.g. subject
 *   CN or DN). Only populated for certificates whose metadata was available at the time
 *   the warning was captured.
 */
@Serializable
data class AnnotatedWarning(
	val summary: LocalizableText,
	val affectedIds: List<String> = emptyList(),
	val idNames: Map<String, String> = emptyMap(),
)



