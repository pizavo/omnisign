package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

/**
 * Wasm/JS actual: hand the report text to FileKit's browser download flow.
 *
 * The browser has no filesystem to write to, so [suggestedName] and [extension] are combined
 * into the download's `fileName`; FileKit wraps the UTF-8 bytes in a Blob and triggers a
 * download anchor click, and the browser routes the file to the user's downloads directory.
 * Mirrors [writeBytesToPath] (the signed-PDF download path) — both are the web equivalent of a
 * native save dialog, which browser security does not expose to a JS app.
 *
 * @param text The text content to save (e.g. a validation report).
 * @param suggestedName Default file name (without extension).
 * @param extension File extension without the dot (e.g. `"txt"`, `"json"`).
 */
actual suspend fun exportTextToFile(text: String, suggestedName: String, extension: String) {
    FileKit.download(
        bytes = text.encodeToByteArray(),
        fileName = "$suggestedName.$extension",
    )
}
