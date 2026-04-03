package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.ui.model.PdfDocumentInfo

/**
 * Wasm/JS stub — browser targets do not support direct filesystem access.
 */
actual suspend fun loadPdfFromPath(filePath: String): PdfDocumentInfo? = null

