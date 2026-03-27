package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile

/**
 * Wasm/JS stub — browser files do not expose a filesystem path.
 */
actual fun platformFilePath(file: PlatformFile): String? = null

