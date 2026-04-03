package cz.pizavo.omnisign.ui.platform

/**
 * Opens a platform file-saver dialog and writes the given text to the chosen location.
 *
 * On JVM this delegates to FileKit's native save dialog. On Wasm/JS this is a no-op
 * (browser downloads may be added in the future).
 *
 * @param text The text content to save.
 * @param suggestedName Default filename (without extension) suggested in the dialog.
 * @param extension File extension (e.g. `"txt"`).
 */
expect suspend fun exportTextToFile(text: String, suggestedName: String, extension: String)

