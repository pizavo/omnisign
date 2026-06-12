package cz.pizavo.omnisign.ui.platform

/**
 * Show a native "save file" dialog and return the chosen destination path.
 *
 * The file is **not** written here — the caller writes the produced bytes to the returned
 * path via [writeBytesToPath]. This keeps the save location a user choice (a native dialog)
 * rather than a hand-typed path.
 *
 * @param suggestedName Default file-name stem, without the [extension].
 * @param extension Default file extension to append, without a leading dot (e.g. `"pdf"`).
 * @param initialDirectory Directory to open the dialog in, or `null` for the platform default.
 * @param allowedExtensions Extensions (without dots) offered in the dialog's "save as type"
 *   dropdown, with [extension] as the default; the chosen one is reflected in the returned path so
 *   the caller can derive the output format from it. Empty means only [extension] is offered. A
 *   native hint, not a validation guarantee — and ignored on the web target (no native dialog).
 * @return The chosen absolute file path, or `null` when the user cancels. On the web target —
 *   where the browser exposes no save location — returns `"<suggestedName>.<extension>"` so the
 *   caller's subsequent [writeBytesToPath] triggers a browser download under that name.
 */
expect suspend fun chooseSaveDestination(
	suggestedName: String,
	extension: String,
	initialDirectory: String? = null,
	allowedExtensions: Set<String> = emptySet(),
): String?
