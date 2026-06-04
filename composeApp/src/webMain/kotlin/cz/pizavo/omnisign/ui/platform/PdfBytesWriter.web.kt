package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

/**
 * Wasm/JS actual that hands [bytes] to FileKit's web download flow.
 *
 * The browser has no filesystem to write to via an absolute path, so [path] is
 * treated as a suggested file name — only the trailing component after `/` or
 * `\` is used as the download's `fileName`. FileKit wraps the bytes in a Blob
 * and triggers a download anchor click; the browser then routes the file to
 * the user's downloads directory (subject to any per-site "always ask where
 * to save" preference).
 *
 * `FileKit.openFileSaver` is not available on the web target (browser security
 * does not permit a JS app to pick an arbitrary save location); `FileKit.download`
 * is the documented web equivalent and what the upstream docs steer wasmJs
 * targets toward.
 *
 * Returns `null` on success and a short error description on failure; the
 * exception is intentionally not propagated so the calling ViewModel can
 * transition into its error state without an unhandled throwable.
 */
actual suspend fun writeBytesToPath(path: String, bytes: ByteArray): String? {
    return try {
        val filename = path
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifEmpty { "document.pdf" }
        FileKit.download(bytes = bytes, fileName = filename)
        null
    } catch (e: Throwable) {
        e.message ?: e::class.simpleName ?: "Unknown error triggering browser download"
    }
}
