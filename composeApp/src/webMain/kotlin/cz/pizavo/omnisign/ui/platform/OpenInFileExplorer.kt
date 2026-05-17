package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS stub — the browser sandbox cannot reveal a local path in a file
 * manager, so this always reports failure.  UI callers fall back to showing the
 * path as plain text.
 */
actual fun openInFileExplorer(path: String): Boolean = false
