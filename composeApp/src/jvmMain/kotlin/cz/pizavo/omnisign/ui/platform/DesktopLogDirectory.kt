package cz.pizavo.omnisign.ui.platform

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolve the platform-native log directory of the desktop application.
 *
 * - **Windows**: `%LOCALAPPDATA%/omnisign/logs`, or `~/AppData/Local/omnisign/logs` without it
 * - **macOS**: `~/Library/Logs/omnisign`
 * - **Linux and other systems**: `$XDG_STATE_HOME/omnisign`, or `~/.local/state/omnisign` without it
 *
 * A blank `LOCALAPPDATA` or `XDG_STATE_HOME` counts as unset, and so does an `XDG_STATE_HOME` that is not
 * an absolute path, as the XDG Base Directory specification requires. Taken as given, an empty value
 * would put the logs in `/omnisign`, which a user cannot create, so no log file would be written.
 *
 * @param osName The `os.name` system property.
 * @param userHome The `user.home` system property.
 * @param environment Return the value of the environment variable it is given, or `null` when it is not
 *   set.
 */
internal fun desktopLogDirectory(osName: String, userHome: String, environment: (String) -> String?): Path {
	val os = osName.lowercase()
	return when {
		os.contains("win") ->
			Path(environment("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$userHome/AppData/Local", "omnisign", "logs")

		os.contains("mac") ->
			Path(userHome, "Library", "Logs", "omnisign")

		else ->
			Path(environment("XDG_STATE_HOME")?.takeIf { it.startsWith("/") } ?: "$userHome/.local/state", "omnisign")
	}
}
