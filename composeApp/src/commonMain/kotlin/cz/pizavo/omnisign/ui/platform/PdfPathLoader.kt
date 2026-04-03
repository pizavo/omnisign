package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.ui.model.PdfDocumentInfo

/**
 * Loads a PDF document from a filesystem path and returns a [PdfDocumentInfo].
 *
 * On JVM this reads the file directly from disk. On Wasm/JS this is a no-op
 * that returns `null` because browser targets do not have direct filesystem access.
 *
 * @param filePath Absolute path to the PDF file.
 * @return A [PdfDocumentInfo] with the file contents, or `null` on platforms
 *   that do not support filesystem access.
 */
expect suspend fun loadPdfFromPath(filePath: String): PdfDocumentInfo?

