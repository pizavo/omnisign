package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import java.io.File

/**
 * JVM implementation of [loadPdfFromPath] backed by [java.io.File] and Apache PDFBox.
 */
actual suspend fun loadPdfFromPath(filePath: String): PdfDocumentInfo? {
	val file = File(filePath)
	if (!file.exists()) return null
	val bytes = file.readBytes()
	val pageCount = getPdfPageCount(bytes)
	return PdfDocumentInfo(
		name = file.name,
		data = bytes,
		pageCount = pageCount,
		filePath = filePath,
	)
}

