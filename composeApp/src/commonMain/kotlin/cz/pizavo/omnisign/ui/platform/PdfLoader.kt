package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

/**
 * Reads a [PlatformFile] and converts it into a [PdfDocumentInfo] ready for the viewer.
 *
 * This suspending function is the single source of truth for loading a PDF from any
 * source (file picker or programmatic open). It reads the raw bytes, delegates
 * page-count extraction to the platform-specific [getPdfPageCount], and returns a
 * fully populated [PdfDocumentInfo].
 *
 * @param file The platform file to read.
 * @return A [PdfDocumentInfo] containing the file name, raw bytes, and page count.
 */
suspend fun loadPdfFromPlatformFile(file: PlatformFile): PdfDocumentInfo {
    val bytes = file.readBytes()
    val pageCount = getPdfPageCount(bytes)
    return PdfDocumentInfo(
        name = file.name,
        data = bytes,
        pageCount = pageCount,
    )
}


