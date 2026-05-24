package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile

/**
 * Read the raw bytes of a certificate [file] selected via a file picker.
 *
 * The returned bytes (PEM or DER) are handed to
 * [cz.pizavo.omnisign.domain.repository.TrustStore.add] for parsing and storage.
 * On JVM this reads the file from disk; on Wasm/JS this returns `null` because the
 * app-managed trust store has no browser backend.
 *
 * @param file Platform file selected by the user via a file picker.
 * @return Raw certificate file bytes, or `null` when the platform cannot read the file
 *   (web), or when [file] cannot be resolved to a filesystem path.
 */
expect fun readCertificateFileBytes(file: PlatformFile): ByteArray?

/**
 * Read the raw bytes of a certificate file at the given filesystem [path].
 *
 * This overload is used when the user types a certificate path manually instead of
 * using the file picker. On JVM this reads the file from disk; on Wasm/JS this returns
 * `null` because filesystem access is not available in the browser.
 *
 * @param path Absolute or relative path to a PEM or DER certificate file.
 * @return Raw certificate file bytes, or `null` when the platform cannot read the file (web).
 */
expect fun readCertificateFileBytesFromPath(path: String): ByteArray?
