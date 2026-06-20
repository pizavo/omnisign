package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * Platform hook for overriding the app's resource locale at runtime.
 *
 * Provide it at the top of the composition:
 * `CompositionLocalProvider(LocalAppLocale provides tag) { ... }`. A `null` tag means "follow the
 * system / browser locale"; a BCP 47 tag like `"en"` or `"cs"` forces that language.
 *
 * It is backed by a `staticCompositionLocalOf`, so changing the provided value recomposes the entire
 * provided scope — every `stringResource` / `pluralStringResource` re-resolves against the new
 * locale — **without** disposing the subtree, so UI and ViewModel state survive the switch.
 */
expect object LocalAppLocale {

	/** The active language tag (e.g. `"en"`, `"cs"`), or the system default when none is forced. */
	val current: String
		@Composable get

	/** Apply [languageTag] (`null` = system default) and return the value to provide for the scope. */
	@Composable
	infix fun provides(languageTag: String?): ProvidedValue<*>
}
