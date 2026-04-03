package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.github.vinceglb.filekit.PlatformFile

/**
 * Wasm/JS stub — X.509 certificate parsing is not available in the browser.
 */
actual fun readCertificateFile(
    name: String,
    file: PlatformFile,
    type: TrustedCertificateType,
): TrustedCertificateConfig? = null

/**
 * Wasm/JS stub — filesystem access is not available in the browser.
 */
actual fun readCertificateFileFromPath(
    name: String,
    path: String,
    type: TrustedCertificateType,
): TrustedCertificateConfig? = null
