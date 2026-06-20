package cz.pizavo.omnisign.data.preferences

import cz.pizavo.omnisign.data.repository.appConfigDirectory
import cz.pizavo.omnisign.domain.model.value.FormatPreferences
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

/** Resolves the `preferences/format.json` path under the application configuration directory. */
private val formatPreferencesPath: Path by lazy {
	appConfigDirectory().resolve("preferences").resolve("format.json")
}

/**
 * Reads the persisted cross-surface [FormatPreferences], or `null` when no preference has been saved
 * yet (the file does not exist) or it cannot be read.
 */
fun loadFormatPreferencesOrNull(): FormatPreferences? = try {
	if (!formatPreferencesPath.exists()) null
	else json.decodeFromString(FormatPreferences.serializer(), formatPreferencesPath.readText())
} catch (e: Exception) {
	logger.warn(e) { "Failed to load format preferences from $formatPreferencesPath" }
	null
}

/**
 * Reads the persisted [FormatPreferences], falling back to the defaults when none have been saved.
 *
 * Convenient for read-only consumers — such as the CLI's date output — that always need a usable
 * value regardless of whether the user has chosen a format.
 */
fun loadFormatPreferences(): FormatPreferences = loadFormatPreferencesOrNull() ?: FormatPreferences()

/**
 * Persists [preferences] to `preferences/format.json`, creating the directory if necessary.
 *
 * This is the single on-disk source of truth shared by the desktop GUI and the CLI, so a format set
 * through either surface is read by the other.
 */
fun saveFormatPreferences(preferences: FormatPreferences) {
	try {
		formatPreferencesPath.parent?.createDirectories()
		formatPreferencesPath.writeText(json.encodeToString(FormatPreferences.serializer(), preferences))
	} catch (e: Exception) {
		logger.warn(e) { "Failed to save format preferences to $formatPreferencesPath" }
	}
}
