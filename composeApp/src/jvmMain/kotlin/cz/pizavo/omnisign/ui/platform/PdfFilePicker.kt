package cz.pizavo.omnisign.ui.platform

import org.apache.pdfbox.Loader

/**
 * JVM implementation of [getPdfPageCount] backed by Apache PDFBox.
 */
actual fun getPdfPageCount(pdfData: ByteArray): Int =
    Loader.loadPDF(pdfData).use { it.numberOfPages }

