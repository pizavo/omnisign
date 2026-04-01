package cz.pizavo.omnisign.ui.platform

import kotlinx.browser.localStorage

private const val KEY = "omnisign.theme.isDark"

/**
 * Wasm/JS implementation — reads the theme preference from the browser's `localStorage`.
 */
actual fun loadThemePreference(): Boolean? = try {
	localStorage.getItem(KEY)?.toBooleanStrictOrNull()
} catch (_: Exception) {
	null
}

/**
 * Wasm/JS implementation — writes the theme preference to the browser's `localStorage`.
 */
actual fun saveThemePreference(isDark: Boolean) {
	try {
		localStorage.setItem(KEY, isDark.toString())
	} catch (_: Exception) {
	}
}

