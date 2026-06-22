package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import cz.pizavo.omnisign.ui.model.UiPreferences

/**
 * Loads the full set of [UiPreferences], composing the chrome fields from `preferences/ui.json` with
 * the cross-surface [FormatPreferences] read from the shared `preferences/format.json` store.
 */
fun loadUiPreferences(): UiPreferences =
	readUiPreferences().copy(format = FormatPreferences(readFormatPreference() ?: DateFormat.SYSTEM))

/**
 * Loads only the persisted native-title-bar preference (`true`/`false`, or `null` when unset).
 *
 * Read on its own at desktop startup and by the settings dialog, before the rest of the UI
 * preferences are needed.
 */
fun loadUseNativeTitleBar(): Boolean? = readUiPreferences().useNativeTitleBar

/** Persists the dark/light theme choice, leaving the other `ui.json` fields untouched. */
fun saveThemePreference(isDark: Boolean) {
	writeUiPreferences(readUiPreferences().copy(isDark = isDark))
}

/** Persists the native-title-bar choice, leaving the other `ui.json` fields untouched. */
fun saveUseNativeTitleBar(useNative: Boolean) {
	writeUiPreferences(readUiPreferences().copy(useNativeTitleBar = useNative))
}

/** Persists the forced UI language tag (`null` = system default), leaving the other fields untouched. */
fun saveLanguagePreference(languageTag: String?) {
	writeUiPreferences(readUiPreferences().copy(languageTag = languageTag))
}

/** Persists the cross-surface date format to the shared `preferences/format.json` store. */
fun saveFormatPreference(dateFormat: DateFormat?) {
	writeFormatPreference(dateFormat ?: DateFormat.SYSTEM)
}

/** Reads the chrome preferences from `preferences/ui.json` (defaults when absent or unreadable). */
internal expect fun readUiPreferences(): UiPreferences

/** Writes the chrome preferences to `preferences/ui.json`; the [UiPreferences.format] field is never written. */
internal expect fun writeUiPreferences(preferences: UiPreferences)

/** Reads the cross-surface date format from the shared store, or `null` when none is saved. */
internal expect fun readFormatPreference(): DateFormat?

/** Writes the cross-surface date format to the shared store. */
internal expect fun writeFormatPreference(dateFormat: DateFormat)
