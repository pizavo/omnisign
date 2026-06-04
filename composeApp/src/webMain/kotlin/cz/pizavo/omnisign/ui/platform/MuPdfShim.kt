@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.ui.platform

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Kotlin/Wasm binding for the MuPDF JavaScript shim that wraps the
 * [`mupdf`](https://www.npmjs.com/package/mupdf) npm package.
 *
 * The shim ([`mupdf-shim.js`][cz.pizavo.omnisign.ui.platform]) hides MuPDF's
 * handle-and-destroy lifecycle (Document → Page → Pixmap, each requiring an
 * explicit `destroy()`) behind a small surface of pure functions. The MuPDF
 * WebAssembly module is loaded asynchronously by mupdf's own top-level await;
 * by the time this `external object`'s functions are callable, the engine is
 * fully initialized.
 *
 * Importing the shim is enough to trigger WASM load — [init] is provided so
 * that call sites can express the boot-time dependency explicitly without
 * any other observable side effect.
 */
@JsModule("./mupdf-shim.js")
external object MuPdfShim {
    /**
     * No-op entry point used to express an explicit dependency on the MuPDF
     * WebAssembly module being loaded. Calling it has no side effect beyond
     * the act of importing the shim, which itself blocks until the engine's
     * top-level WASM init has resolved.
     */
    fun init()

    /**
     * Returns the page count of [bytes] (a PDF document).
     *
     * Opens a transient MuPDF [`Document`][mupdf-shim] handle, reads the page
     * count, and destroys the handle. Subsequent calls re-parse — V1 mirrors
     * the JVM/PDFBox per-call lifecycle exactly.
     *
     * @param bytes Raw PDF bytes.
     * @return Total page count.
     */
    fun getPageCount(bytes: Uint8Array): Int

    /**
     * Renders the page at [pageIndex] (zero-based) to PNG bytes at the
     * requested [scale], where `scale = 2.0` corresponds to 144 DPI
     * (matching the JVM default in
     * [rememberPdfPageBitmap][cz.pizavo.omnisign.ui.platform.rememberPdfPageBitmap]).
     *
     * Opens a transient `Document → Page → Pixmap` chain, encodes the pixmap
     * to PNG, and destroys every intermediate handle before returning.
     *
     * @param bytes Raw PDF bytes.
     * @param pageIndex Zero-based page index.
     * @param scale Multiplier applied to the base 72 DPI resolution.
     * @return PNG-encoded page bitmap.
     */
    fun renderPagePng(bytes: Uint8Array, pageIndex: Int, scale: Double): Uint8Array
}

/**
 * Copies the contents of this Kotlin [ByteArray] into a fresh JS [Uint8Array]
 * for passing across the Kotlin/Wasm ↔ JS boundary.
 *
 * Kotlin's `ByteArray` is not directly addressable from JavaScript; consumers
 * of the [MuPdfShim] external surface convert via this helper before calling.
 */
internal fun ByteArray.toUint8Array(): Uint8Array {
    val target = Uint8Array(size)
    for (i in indices) {
        target[i] = this[i]
    }
    return target
}

/**
 * Copies the contents of this JS [Uint8Array] into a fresh Kotlin [ByteArray]
 * so the bytes can be consumed by JVM-agnostic APIs such as Skia's
 * `Image.makeFromEncoded`.
 *
 * The inverse of [toUint8Array]; used on the return path from [MuPdfShim]
 * functions that emit PNG bytes.
 */
internal fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(length)
    for (i in 0 until length) {
        result[i] = this[i]
    }
    return result
}
