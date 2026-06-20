package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.preferences.loadFormatPreferencesOrNull
import cz.pizavo.omnisign.data.preferences.saveFormatPreferences
import cz.pizavo.omnisign.data.repository.appConfigDirectory
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
import cz.pizavo.omnisign.ui.model.UiPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

private val json = Json {
	prettyPrint = true
	ignoreUnknownKeys = true
}

/** Resolves the `preferences/ui.json` path under the application configuration directory. */
private val uiPreferencesPath: Path by lazy {
	appConfigDirectory().resolve("preferences").resolve("ui.json")
}

/**
 * JVM implementation — reads the chrome preferences from `preferences/ui.json`, returning defaults
 * when the file is absent or unreadable.
 */
internal actual fun readUiPreferences(): UiPreferences = try {
	if (!uiPreferencesPath.exists()) UiPreferences()
	else json.decodeFromString(UiPreferences.serializer(), uiPreferencesPath.readText())
} catch (e: Exception) {
	logger.warn(e) { "Failed to load UI preferences from $uiPreferencesPath" }
	UiPreferences()
}

/**
 * JVM implementation — writes the chrome preferences to `preferences/ui.json`. The
 * [UiPreferences.format] field is `@Transient`, so it is never serialized here.
 */
internal actual fun writeUiPreferences(preferences: UiPreferences) {
	try {
		uiPreferencesPath.parent?.createDirectories()
		uiPreferencesPath.writeText(json.encodeToString(UiPreferences.serializer(), preferences))
	} catch (e: Exception) {
		logger.warn(e) { "Failed to save UI preferences to $uiPreferencesPath" }
	}
}

/** JVM implementation — delegates to the shared `preferences/format.json` store. */
internal actual fun readFormatPreference(): DateFormat? = loadFormatPreferencesOrNull()?.dateFormat

/** JVM implementation — delegates to the shared `preferences/format.json` store. */
internal actual fun writeFormatPreference(dateFormat: DateFormat) {
	saveFormatPreferences(FormatPreferences(dateFormat))
}
