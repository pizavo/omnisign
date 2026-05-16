package cz.pizavo.omnisign.data.service

import java.io.File

/**
 * Resolve the platform-appropriate PKCS#11 drop directory: `<appDataDir>/omnisign/pkcs11/`.
 *
 * Files placed here are discovered automatically by [Pkcs11Discoverer] without any config
 * change.  The directory does not need to exist — discovery treats a missing or empty drop
 * directory as a no-op contribution to the candidate list.
 *
 * - **Windows**: `%APPDATA%/omnisign/pkcs11`
 * - **macOS**: `~/Library/Application Support/omnisign/pkcs11`
 * - **Linux/other**: `~/.config/omnisign/pkcs11`
 *
 * Centralised here so the desktop entry point, the JVM token service, and the cache
 * invalidator all agree on the same location — discovery results cache by `(dropDir,
 * userLibs)`, so a mismatch would split the cache across two keys and defeat warmup
 * priming or proactive rediscovery.
 *
 * @return The drop directory [File]; existence on disk is **not** asserted.
 */
fun pkcs11DropDir(): File {
	val os = System.getProperty("os.name").lowercase()
	val userHome = System.getProperty("user.home")
	val base = when {
		os.contains("win") -> System.getenv("APPDATA")?.let { File(it, "omnisign") }
			?: File(userHome, "AppData/Roaming/omnisign")
		os.contains("mac") -> File(userHome, "Library/Application Support/omnisign")
		else -> File(userHome, ".config/omnisign")
	}
	return File(base, "pkcs11")
}
