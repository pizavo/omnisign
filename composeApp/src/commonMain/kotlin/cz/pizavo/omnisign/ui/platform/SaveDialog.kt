package cz.pizavo.omnisign.ui.platform

/**
 * Show a native "save file" dialog and return the chosen destination path.
 *
 * The file is **not** written here — the caller writes the produced bytes to the returned
 * path via [writeBytesToPath]. This keeps the save location a user choice (a native dialog)
 * rather than a hand-typed path.
 *
 * @param suggestedName Default file-name stem, without the [extension].
 * @param extension File extension to append, without a leading dot (e.g. `"pdf"`).
 * @param initialDirectory Directory to open the dialog in, or `null` for the platform default.
 * @return The chosen absolute file path, or `null` when the user cancels. On the web target —
 *   where the browser exposes no save location — returns `"<suggestedName>.<extension>"` so the
 *   caller's subsequent [writeBytesToPath] triggers a browser download under that name.
 */
expect suspend fun chooseSaveDestination(
	suggestedName: String,
	extension: String,
	initialDirectory: String? = null,
): String?
