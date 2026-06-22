package cz.pizavo.omnisign.data.repository

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the platform-native OmniSign configuration directory:
 * - **Windows**: `%APPDATA%/omnisign` (fallback `~/AppData/Roaming/omnisign`)
 * - **macOS**: `~/Library/Application Support/omnisign`
 * - **Linux/other**: `~/.config/omnisign`
 *
 * This is the single source of truth for the config-directory convention shared by
 * [FileConfigRepository] (`config.json`), the user-preference stores (`preferences/`), and the
 * trust store — every consumer resolves the directory through this function rather than re-deriving
 * the platform `when`.
 */
fun appConfigDirectory(): Path {
	val userHome = System.getProperty("user.home")
	val os = System.getProperty("os.name").lowercase()
	return when {
		os.contains("win") ->
			System.getenv("APPDATA")?.let { Paths.get(it, "omnisign") }
				?: Paths.get(userHome, "AppData", "Roaming", "omnisign")

		os.contains("mac") ->
			Paths.get(userHome, "Library", "Application Support", "omnisign")

		else ->
			Paths.get(userHome, ".config", "omnisign")
	}
}
