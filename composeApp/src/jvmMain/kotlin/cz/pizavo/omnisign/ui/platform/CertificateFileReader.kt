package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.service.TrustedCertificateReader
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import java.io.File

/**
 * JVM implementation — resolves the [PlatformFile] to a filesystem path and
 * delegates to [TrustedCertificateReader] for X.509 parsing.
 */
@Suppress("UselessElvis")
actual fun readCertificateFile(
    name: String,
    file: PlatformFile,
    type: TrustedCertificateType,
): TrustedCertificateConfig? {
    val path = file.absolutePath()
    return TrustedCertificateReader.read(name, File(path), type)
}

/**
 * JVM implementation — reads the certificate from the given filesystem [path].
 */
actual fun readCertificateFileFromPath(
    name: String,
    path: String,
    type: TrustedCertificateType,
): TrustedCertificateConfig? {
    return TrustedCertificateReader.read(name, File(path), type)
}
