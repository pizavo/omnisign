package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.window

private fun browserLanguageTag(): String = window.navigator.language.ifBlank { "en" }

private val LocalLanguageTag = staticCompositionLocalOf { browserLanguageTag() }

/**
 * Web (Wasm) implementation.
 *
 * Compose Resources on the web resolves strings from the browser locale, and the JVM
 * [java.util.Locale] override is unavailable here. [provides] tracks the chosen tag (recomposing the
 * scope) and reflects it on the document's `lang` attribute for accessibility, but the resolved
 * resource locale still follows the browser — full runtime language switching on the web target is a
 * known limitation (the web build degrades gracefully to the browser language).
 */
actual object LocalAppLocale {

	actual val current: String
		@Composable get() = LocalLanguageTag.current

	@Composable
	actual infix fun provides(languageTag: String?): ProvidedValue<*> {
		val tag = languageTag ?: browserLanguageTag()
		window.document.documentElement?.setAttribute("lang", tag)
		return LocalLanguageTag.provides(tag)
	}
}
