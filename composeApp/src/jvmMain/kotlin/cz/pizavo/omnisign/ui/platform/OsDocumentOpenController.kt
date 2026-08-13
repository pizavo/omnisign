package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * JVM [DocumentOpenController] fed by the two ways a desktop OS hands a document to an app.
 *
 * Windows and Linux pass the file as the launcher's first command-line argument: the MSI
 * registers `"%1" %*` on the PDF ProgID, and the Linux desktop entry needs `Exec=… %U`. macOS
 * does not use the command line at all — it delivers an `openFiles` Apple event after the JVM
 * has started, which `main` forwards through [offer].
 *
 * @param initial File named on the command line, or `null` when the app was launched without one.
 */
class OsDocumentOpenController(initial: PlatformFile? = null) : DocumentOpenController {

	private val _request = MutableStateFlow(initial)

	override val request: StateFlow<PlatformFile?> = _request.asStateFlow()

	override fun consume() {
		_request.value = null
	}

	/**
	 * Publishes a file the operating system asked to open after startup.
	 *
	 * @param file The document to display.
	 */
	fun offer(file: PlatformFile) {
		_request.value = file
	}

	companion object {

		/**
		 * Resolves the document a launch was asked to open from its command-line arguments.
		 *
		 * Only the first argument is considered, and only when it names an existing regular
		 * file. The desktop launcher doubles as the entry point for the internal `renew`,
		 * `probe` and `discover-modules` verbs, and those are dispatched before this is
		 * consulted; requiring an existing file keeps any future verb from being mistaken for
		 * a path and surfacing a spurious load failure.
		 *
		 * @param args The arguments `main` received.
		 * @return The file to open, or `null` when the launch named none.
		 */
		fun fromArgs(args: Array<String>): PlatformFile? =
			args.firstOrNull()
				?.let { File(it) }
				?.takeIf { it.isFile }
				?.let { PlatformFile(it.absoluteFile) }
	}
}
