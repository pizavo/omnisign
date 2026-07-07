package cz.pizavo.omnisign.ui.platform

/**
 * JVM / desktop implementation — opens the native save dialog via [chooseSaveDestination] and writes
 * the bytes to the chosen path via [writeBytesToPath]. The desktop always knows the destination, so
 * it never returns [SaveOutcome.SavedNameUnknown].
 */
actual suspend fun saveDocument(
	bytes: ByteArray,
	suggestedName: String,
	extension: String,
	initialDirectory: String?,
): SaveOutcome {
	val path = chooseSaveDestination(suggestedName, extension, initialDirectory) ?: return SaveOutcome.Cancelled
	val error = writeBytesToPath(path, bytes)
	return if (error != null) SaveOutcome.Failed(error) else SaveOutcome.Saved(path)
}
