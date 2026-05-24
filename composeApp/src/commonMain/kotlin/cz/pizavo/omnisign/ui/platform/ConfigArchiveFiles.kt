package cz.pizavo.omnisign.ui.platform

/**
 * Open a native save dialog and write the configuration export [bytes] to the chosen file.
 *
 * @param bytes The ZIP archive bytes to write.
 * @param suggestedName Default file name (without extension) offered in the dialog.
 * @return `true` when a file was written, `false` when the user cancelled or the platform has no
 *   file-system backend (web).
 */
expect suspend fun exportConfigArchive(bytes: ByteArray, suggestedName: String): Boolean

/**
 * Open a native open dialog for a ZIP configuration archive and return its bytes.
 *
 * @return The chosen archive's bytes, or `null` when the user cancelled or the platform has no
 *   file-system backend (web).
 */
expect suspend fun importConfigArchive(): ByteArray?
