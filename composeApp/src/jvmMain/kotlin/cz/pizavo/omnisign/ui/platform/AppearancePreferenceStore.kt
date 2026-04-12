package cz.pizavo.omnisign.ui.platform

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

private const val FILE_NAME = "appearance.properties"
private const val KEY = "useNativeTitleBar"

/**
 * Resolves the platform-native path for the appearance preferences file,
 * matching the convention used by [WindowStateStore] and [ThemePreferenceStore][loadThemePreference].
 */
private val storagePath: Path by lazy {
	val userHome = System.getProperty("user.home")
	val os = System.getProperty("os.name").lowercase()
	val dir = when {
		os.contains("win") ->
			Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "omnisign")

		os.contains("mac") ->
			Paths.get(userHome, "Library", "Application Support", "omnisign")

		else ->
			Paths.get(userHome, ".config", "omnisign")
	}
	dir.resolve(FILE_NAME)
}

/**
 * JVM implementation — reads the native title bar preference from a properties file
 * stored in the platform-native configuration directory.
 */
actual fun loadUseNativeTitleBar(): Boolean? = try {
	if (!storagePath.exists()) null
	else {
		val props = Properties()
		storagePath.inputStream().use { props.load(it) }
		props.getProperty(KEY)?.toBooleanStrictOrNull()
	}
} catch (e: Exception) {
	logger.warn(e) { "Failed to load appearance preference from $storagePath" }
	null
}

/**
 * JVM implementation — writes the native title bar preference to a properties file
 * stored in the platform-native configuration directory.
 */
actual fun saveUseNativeTitleBar(useNative: Boolean) {
	try {
		storagePath.parent?.createDirectories()
		val props = Properties()
		props.setProperty(KEY, useNative.toString())
		storagePath.outputStream().use { props.store(it, "OmniSign appearance preference") }
	} catch (e: Exception) {
		logger.warn(e) { "Failed to save appearance preference to $storagePath" }
	}
}
