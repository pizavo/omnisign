package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlin.io.path.Path
import kotlin.io.path.readBytes

/**
 * JVM implementation — resolves the [PlatformFile] to a filesystem path and reads its bytes.
 */
actual fun readCertificateFileBytes(file: PlatformFile): ByteArray? = Path(file.absolutePath()).readBytes()

/**
 * JVM implementation — reads the certificate file bytes from the given filesystem [path].
 */
actual fun readCertificateFileBytesFromPath(path: String): ByteArray? = Path(path).readBytes()
