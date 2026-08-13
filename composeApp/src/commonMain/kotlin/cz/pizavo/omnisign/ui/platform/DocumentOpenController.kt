package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic source of "open this document" requests issued by the operating system.
 *
 * A packaged OmniSign registers itself as a PDF handler (the `--file-associations` wiring in
 * `composeApp/build.gradle.kts`), so the shell can ask a freshly launched app to display one
 * specific file. Windows and Linux deliver that file as a command-line argument; macOS sends an
 * Apple event once the JVM is already running. The JVM implementation
 * ([OsDocumentOpenController]) normalises both into this single-slot surface, mirroring how
 * [PasswordDialogController] exposes a pending password prompt.
 *
 * The Compose UI observes [request], loads the file through [loadPdfFromPlatformFile], and then
 * calls [consume] so the same document is not reopened on a later recomposition. The web target
 * has no equivalent concept and leaves this port unbound.
 */
interface DocumentOpenController {

	/**
	 * File the operating system asked the app to open.
	 *
	 * Non-null while a request is waiting to be displayed; `null` once the UI has taken it or
	 * when the app was launched without a document.
	 */
	val request: StateFlow<PlatformFile?>

	/**
	 * Clears the pending request after the UI has loaded it.
	 */
	fun consume()
}
