package cz.pizavo.omnisign.ui.platform

/**
 * Prompt the user for a destination and write [bytes] there, reporting what happened.
 *
 * Combines the "pick a location" and "write" steps so the web target can use the File System Access
 * API (`showSaveFilePicker`), which yields the chosen file name only as part of the same call, and
 * degrade to a plain download otherwise:
 * - **Desktop** — native save dialog, then write; always reports the full path ([SaveOutcome.Saved]).
 * - **Web** — `showSaveFilePicker` when supported (reports the chosen name, [SaveOutcome.Saved]);
 *   otherwise a browser download whose final name is unknown ([SaveOutcome.SavedNameUnknown]).
 *
 * @param bytes The document bytes to persist.
 * @param suggestedName Default file-name stem (no extension).
 * @param extension File extension without the dot (e.g. `"pdf"`).
 * @param initialDirectory Directory to seed the dialog with; ignored where the platform has no
 *   concept of one (web).
 * @return A [SaveOutcome] describing whether the file was saved (and under a known name), cancelled,
 *   or failed.
 */
expect suspend fun saveDocument(
	bytes: ByteArray,
	suggestedName: String,
	extension: String,
	initialDirectory: String?,
): SaveOutcome
