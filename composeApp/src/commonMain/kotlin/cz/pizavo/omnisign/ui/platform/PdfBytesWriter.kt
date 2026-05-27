package cz.pizavo.omnisign.ui.platform

/**
 * Persist the signing / timestamp result [bytes] to the platform's idiomatic save target.
 *
 * The [path] argument is treated as a full filesystem path on the JVM (desktop, CLI)
 * and as a suggested file name on the web (only the trailing component is meaningful —
 * browsers cannot accept an absolute target path due to sandboxing). On the desktop
 * the function writes the bytes directly to that path; on the web target it delegates
 * to FileKit's `download` flow which materializes the bytes as a Blob and triggers
 * the browser's standard download (the file lands in the user's downloads directory,
 * subject to whatever per-site "always ask where to save" preference the user has).
 *
 * @param path Absolute filesystem path on JVM; suggested file name on web (the
 *   trailing `/` or `\` component is used as the download name there).
 * @param bytes Bytes to persist.
 * @return `null` on success, or a short error description suitable for surfacing in a
 *   dialog when the write fails. The exception is intentionally not propagated so the
 *   calling ViewModel can transition into its error state without an unhandled throwable.
 */
expect suspend fun writeBytesToPath(path: String, bytes: ByteArray): String?
