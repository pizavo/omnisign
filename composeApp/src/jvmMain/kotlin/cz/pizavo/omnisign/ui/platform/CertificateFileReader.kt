package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.service.TrustedCertificateReader
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import org.koin.mp.KoinPlatform
import java.io.File

/**
 * JVM implementation — resolves the [PlatformFile] to a filesystem path and
 * delegates to the Koin-provided [TrustedCertificateReader] for X.509 parsing.
 */
@Suppress("UselessElvis")
actual fun readCertificateFile(
    name: String,
    file: PlatformFile,
    type: TrustedCertificateType,
): TrustedCertificateConfig? {
    val reader = KoinPlatform.getKoin().get<TrustedCertificateReader>()
    val path = file.absolutePath()
    return reader.read(name, File(path), type)
}

/**
 * JVM implementation — reads the certificate from the given filesystem [path].
 */
actual fun readCertificateFileFromPath(
    name: String,
    path: String,
    type: TrustedCertificateType,
): TrustedCertificateConfig? {
    val reader = KoinPlatform.getKoin().get<TrustedCertificateReader>()
    return reader.read(name, File(path), type)
}
