package cz.pizavo.omnisign.data.repository

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolves the platform-native OmniSign configuration directory:
 * - **Windows**: `%APPDATA%/omnisign` (fallback `~/AppData/Roaming/omnisign`)
 * - **macOS**: `~/Library/Application Support/omnisign`
 * - **Linux/other**: `~/.config/omnisign`
 *
 * `APPDATA` counts only while it holds an absolute path, as [environmentDirectory] reads it.
 *
 * This is the single source of truth for the config-directory convention shared by
 * [FileConfigRepository] (`config.json`), the user-preference stores (`preferences/`), the trust
 * store, the PKCS#11 drop directory, the server's session database, and the desktop's window state
 * and Support preferences — every consumer resolves the directory through this function rather than
 * re-deriving the platform `when`.
 */
fun appConfigDirectory(): Path =
	appConfigDirectory(System.getProperty("os.name"), System.getProperty("user.home"), System::getenv)

/**
 * Resolve the configuration directory [appConfigDirectory] describes on the operating system named
 * [osName] for the home directory [userHome].
 *
 * @param osName The `os.name` system property.
 * @param userHome The `user.home` system property.
 * @param environment Return the value of the environment variable it is given, or `null` when it is
 *   not set.
 */
internal fun appConfigDirectory(osName: String, userHome: String, environment: (String) -> String?): Path {
	val os = osName.lowercase()
	return when {
		os.contains("win") ->
			(environmentDirectory("APPDATA", environment) ?: Path(userHome, "AppData", "Roaming")).resolve("omnisign")

		os.contains("mac") ->
			Path(userHome, "Library", "Application Support", "omnisign")

		else ->
			Path(userHome, ".config", "omnisign")
	}
}

/**
 * The directory the environment variable [name] holds, or `null` when it is not set or holds no
 * absolute path, as an empty value does. The XDG Base Directory specification has a reader ignore such
 * a value, and OmniSign treats `APPDATA` and `LOCALAPPDATA` the same way: taken as given, such a value
 * would put OmniSign's files under the root of the file system, where a user cannot create them, or
 * under whatever directory the process happened to start in.
 *
 * @param name The variable to read, such as `APPDATA` or `XDG_CACHE_HOME`.
 * @param environment Return the value of the environment variable it is given, or `null` when it is
 *   not set.
 */
internal fun environmentDirectory(name: String, environment: (String) -> String?): Path? =
	environment(name)
		?.let { runCatching { Path(it) }.getOrNull() }
		?.takeIf { it.isAbsolute }
