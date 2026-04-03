package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.github.vinceglb.filekit.PlatformFile

/**
 * Parse an X.509 certificate from a [PlatformFile] and return a [TrustedCertificateConfig].
 *
 * On JVM this delegates to [cz.pizavo.omnisign.data.service.TrustedCertificateReader].
 * On Wasm/JS this returns `null` because `java.security.cert.CertificateFactory` is not
 * available in the browser.
 *
 * @param name Human-readable label for the certificate entry.
 * @param file Platform file selected by the user via a file picker.
 * @param type Trust type (ANY, CA, or TSA).
 * @return Parsed [TrustedCertificateConfig] or `null` when the platform does not support
 *   certificate parsing, or when [file] cannot be resolved to a filesystem path.
 */
expect fun readCertificateFile(
    name: String,
    file: PlatformFile,
    type: TrustedCertificateType,
): TrustedCertificateConfig?

/**
 * Parse an X.509 certificate from a filesystem [path] and return a [TrustedCertificateConfig].
 *
 * This overload is used when the user types a certificate path manually instead of
 * using the file picker. On JVM this delegates to
 * [cz.pizavo.omnisign.data.service.TrustedCertificateReader]. On Wasm/JS this returns
 * `null` because filesystem access is not available in the browser.
 *
 * @param name Human-readable label for the certificate entry.
 * @param path Absolute or relative path to a PEM or DER certificate file.
 * @param type Trust type (ANY, CA, or TSA).
 * @return Parsed [TrustedCertificateConfig] or `null` when the platform does not support
 *   certificate parsing.
 */
expect fun readCertificateFileFromPath(
    name: String,
    path: String,
    type: TrustedCertificateType,
): TrustedCertificateConfig?
