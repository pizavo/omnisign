package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import cz.pizavo.omnisign.ui.model.UiPreferences
import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json

/** `localStorage` key holding the serialized chrome [UiPreferences] (the web analogue of `ui.json`). */
private const val UI_KEY = "omnisign.preferences.ui"

/** `localStorage` key holding the serialized [FormatPreferences] (the web analogue of `format.json`). */
private const val FORMAT_KEY = "omnisign.preferences.format"

private val json = Json {
	ignoreUnknownKeys = true
}

/**
 * Wasm/JS implementation — reads the chrome preferences from the browser's `localStorage`, returning
 * defaults when nothing is stored or the value cannot be parsed.
 */
internal actual fun readUiPreferences(): UiPreferences = try {
	localStorage.getItem(UI_KEY)?.let { json.decodeFromString(UiPreferences.serializer(), it) } ?: UiPreferences()
} catch (_: Exception) {
	UiPreferences()
}

/**
 * Wasm/JS implementation — writes the chrome preferences to the browser's `localStorage`. The
 * [UiPreferences.format] field is `@Transient`, so it is never serialized here.
 */
internal actual fun writeUiPreferences(preferences: UiPreferences) {
	try {
		localStorage.setItem(UI_KEY, json.encodeToString(UiPreferences.serializer(), preferences))
	} catch (_: Exception) {
	}
}

/** Wasm/JS implementation — reads the date format from the browser's `localStorage`. */
internal actual fun readFormatPreference(): DateFormat? = try {
	localStorage.getItem(FORMAT_KEY)?.let { json.decodeFromString(FormatPreferences.serializer(), it).dateFormat }
} catch (_: Exception) {
	null
}

/** Wasm/JS implementation — writes the date format to the browser's `localStorage`. */
internal actual fun writeFormatPreference(dateFormat: DateFormat) {
	try {
		localStorage.setItem(FORMAT_KEY, json.encodeToString(FormatPreferences.serializer(), FormatPreferences(dateFormat)))
	} catch (_: Exception) {
	}
}
