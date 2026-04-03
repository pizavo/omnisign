package cz.pizavo.omnisign.ui.platform

/**
 * Returns the number of pages in a PDF document.
 *
 * On JVM this is backed by Apache PDFBox; other platforms will provide
 * their own implementations.
 *
 * @param pdfData Raw bytes of the PDF document.
 * @return Total page count.
 */
expect fun getPdfPageCount(pdfData: ByteArray): Int

