package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver

/**
 * JVM implementation — opens a native save-file dialog via FileKit and returns the chosen
 * destination's absolute path, or `null` when the user cancels. The destination is not written
 * here; the caller writes to the returned path with [writeBytesToPath].
 */
actual suspend fun chooseSaveDestination(
	suggestedName: String,
	extension: String,
	initialDirectory: String?,
): String? = FileKit.openFileSaver(
	suggestedName = suggestedName,
	extension = extension,
	directory = initialDirectory?.let { PlatformFile(it) },
)?.let { platformFilePath(it) }
