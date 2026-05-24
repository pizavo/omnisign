package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile

/**
 * Wasm/JS stub — the app-managed trust store has no browser backend, so certificate
 * file bytes are never read on web.
 */
actual fun readCertificateFileBytes(file: PlatformFile): ByteArray? = null

/**
 * Wasm/JS stub — filesystem access is not available in the browser.
 */
actual fun readCertificateFileBytesFromPath(path: String): ByteArray? = null
