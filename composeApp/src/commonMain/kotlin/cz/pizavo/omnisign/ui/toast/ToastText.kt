package cz.pizavo.omnisign.ui.toast

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * Locale-agnostic description of a toast's text, emitted by callers (view models, dialogs) as
 * plain data and resolved to a display [String] only at render time by the `@Composable`
 * [ToastHost].
 *
 * Mirrors the [cz.pizavo.omnisign.ui.model.ErrorMessage] pattern: the producer stays free of the
 * Compose-resources runtime (no `getString` / `getPluralString`), so emission is synchronous and
 * side-effect-free, and unit tests can assert the typed payload directly without driving the
 * resource loader.  That is what keeps the rescan toast in
 * [cz.pizavo.omnisign.ui.viewmodel.SigningViewModel] deterministic under `runTest`: resolving a
 * [StringResource] off-thread from inside the view model otherwise reads resource bytes on a real
 * background dispatcher that escapes the test's virtual clock, so the toast races the assertion.
 */
sealed interface ToastText {

	/**
	 * Already-resolved or intrinsically non-localized text — a platform/OS error string, a file
	 * name, and the like.  [ToastHost] passes [value] through verbatim.
	 *
	 * @property value The literal message body.
	 */
	data class Raw(val value: String) : ToastText

	/**
	 * A [StringResource] resolved against the active locale, with optional positional format
	 * [args] substituted for its `%1$s`-style placeholders.
	 *
	 * @property resource The string resource to resolve.
	 * @property args Positional format arguments in order; empty for a placeholder-free string.
	 */
	data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : ToastText

	/**
	 * A [PluralStringResource] whose grammatical form is selected by [quantity], with optional
	 * positional format [args] (commonly the count itself) substituted for its placeholders.
	 *
	 * @property resource The plural resource to resolve.
	 * @property quantity The count that selects the plural category (one / other / …).
	 * @property args Positional format arguments in order; empty for a placeholder-free form.
	 */
	data class Plural(
		val resource: PluralStringResource,
		val quantity: Int,
		val args: List<Any> = emptyList(),
	) : ToastText
}
