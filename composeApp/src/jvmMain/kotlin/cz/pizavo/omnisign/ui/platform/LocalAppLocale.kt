package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** The locale active when the app started — restored when the user picks "system default". */
private val systemDefault: Locale = Locale.getDefault()

private val LocalLanguageTag = staticCompositionLocalOf { systemDefault.toLanguageTag() }

/**
 * JVM / desktop implementation.
 *
 * Compose Resources resolves strings against [Locale.getDefault], so [provides] overrides it via
 * [Locale.setDefault] and republishes the tag through a `staticCompositionLocalOf`, recomposing the
 * scope so the new locale takes effect immediately.
 */
actual object LocalAppLocale {

	actual val current: String
		@Composable get() = LocalLanguageTag.current

	@Composable
	actual infix fun provides(languageTag: String?): ProvidedValue<*> {
		val locale = if (languageTag == null) systemDefault else Locale.forLanguageTag(languageTag)
		Locale.setDefault(locale)
		return LocalLanguageTag.provides(locale.toLanguageTag())
	}
}
