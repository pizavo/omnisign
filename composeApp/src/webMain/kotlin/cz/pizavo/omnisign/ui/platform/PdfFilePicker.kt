package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS implementation of [getPdfPageCount] backed by the
 * [MuPdfShim] JavaScript binding around the
 * [`mupdf`](https://www.npmjs.com/package/mupdf) WebAssembly engine.
 *
 * Mirrors the JVM/PDFBox semantics: a fresh document is opened, the count is
 * read, and the document handle is destroyed on the JS side before this
 * function returns. The MuPDF WebAssembly module is pre-initialized at app
 * boot in `main.kt`, so the call is effectively synchronous in practice.
 */
actual fun getPdfPageCount(pdfData: ByteArray): Int =
    MuPdfShim.getPageCount(pdfData.toUint8Array())
