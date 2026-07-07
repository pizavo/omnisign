@file:OptIn(ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.window

/**
 * Write (or clear) the runtime UI-language override that the `index.html` navigator shim reads.
 *
 * Compose Resources resolve strings against the browser's `navigator.language(s)`, which are
 * read-only. `index.html` redefines them to return `globalThis.__customLocale` when it is set, so
 * writing the chosen tag here makes `stringResource` re-resolve to that language; an empty string
 * clears the override so the real browser locale applies again. Known Compose Multiplatform
 * limitation — see https://youtrack.jetbrains.com/projects/CMP/issues/CMP-8376.
 */
private fun writeCustomLocale(tag: String): Unit =
	js("globalThis.__customLocale = tag || null")

/**
 * Seed the navigator-shim override from a persisted language tag before the first composition, so the
 * initial render already uses the chosen language instead of flashing the browser locale. A `null`
 * (or blank) tag clears the override and follows the browser. Called from the web entry point and, on
 * every change, by [LocalAppLocale.provides].
 *
 * @param languageTag BCP-47 tag to force, or `null` to follow the browser locale.
 */
internal fun applyWebLocale(languageTag: String?): Unit =
	writeCustomLocale(languageTag?.replace('_', '-') ?: "")

private fun browserLanguageTag(): String = window.navigator.language.ifBlank { "en" }

private val LocalLanguageTag = staticCompositionLocalOf { browserLanguageTag() }

/**
 * Web (Wasm) implementation.
 *
 * Runtime language switching works by pairing this override with the `navigator.language(s)` shim in
 * `index.html`: [provides] writes the chosen tag to `globalThis.__customLocale` (so the shimmed
 * navigator returns it) and republishes it through a `staticCompositionLocalOf`, which recomposes the
 * whole provided scope. Every `stringResource` then re-reads the now-overridden locale and
 * re-resolves — **without** disposing the subtree, so UI and ViewModel state survive the switch. A
 * `null` tag clears the override and follows the browser locale. The document's `lang` attribute is
 * kept in sync for accessibility.
 */
actual object LocalAppLocale {

	actual val current: String
		@Composable get() = LocalLanguageTag.current

	@Composable
	actual infix fun provides(languageTag: String?): ProvidedValue<*> {
		applyWebLocale(languageTag)
		val tag = languageTag ?: browserLanguageTag()
		window.document.documentElement?.setAttribute("lang", tag)
		return LocalLanguageTag.provides(tag)
	}
}
