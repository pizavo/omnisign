package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.data.repository.appConfigDirectory
import java.io.File

/**
 * System property that, when set, overrides the OS-specific default returned by
 * [sessionsDbFile]. Intended for tests so each run can redirect to a temp file rather
 * than polluting the user's per-user app-data directory.
 */
const val SESSIONS_DB_FILE_PROPERTY = "omnisign.sessions.dbFile"

/**
 * Resolve the platform-appropriate location of the server's refresh-token store SQLite
 * database file: `sessions.db` in the configuration directory [appConfigDirectory] resolves.
 *
 * - **Windows**: `%APPDATA%/omnisign/sessions.db`
 * - **macOS**: `~/Library/Application Support/omnisign/sessions.db`
 * - **Linux/other**: `~/.config/omnisign/sessions.db`
 *
 * The [SESSIONS_DB_FILE_PROPERTY] system property takes precedence when set, redirecting
 * to an arbitrary path — tests use this to write to a temp file.
 *
 * Sibling to the PKCS#11 drop directory (`pkcs11/` there) — both live
 * under the same per-user configuration directory. The parent directory is created on first use
 * by [cz.pizavo.omnisign.auth.ExposedRefreshTokenStore] if it does not exist; existence
 * of the file itself is not asserted here.
 *
 * @return The sessions database [File]; neither parent nor file is required to exist
 *   on disk at the time of this call.
 */
fun sessionsDbFile(): File {
    System.getProperty(SESSIONS_DB_FILE_PROPERTY)?.takeIf { it.isNotBlank() }?.let {
        return File(it)
    }
    return appConfigDirectory().resolve("sessions.db").toFile()
}
