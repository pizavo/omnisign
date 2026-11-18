package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.appConfigDirectory
import java.io.File

/**
 * Resolve the platform-appropriate PKCS#11 drop directory: `pkcs11/` in the configuration
 * directory [appConfigDirectory] resolves.
 *
 * Files placed here are discovered automatically by [Pkcs11Discoverer] without any config
 * change, in the CLI, the desktop app and the server alike.  The directory does not need to
 * exist — discovery treats a missing or empty drop directory as a no-op contribution to the
 * candidate list.
 *
 * - **Windows**: `%APPDATA%/omnisign/pkcs11`
 * - **macOS**: `~/Library/Application Support/omnisign/pkcs11`
 * - **Linux/other**: `~/.config/omnisign/pkcs11`
 *
 * Centralised here so the warmup service and the cache invalidator, which the JVM module builds
 * with it for every entry point, and the token service all agree on the same location —
 * discovery results cache by `(dropDir, userLibs)`, so a mismatch would split the cache across
 * two keys and defeat warmup priming or proactive rediscovery.
 *
 * @return The drop directory [File]; existence on disk is **not** asserted.
 */
fun pkcs11DropDir(): File = appConfigDirectory().resolve("pkcs11").toFile()
