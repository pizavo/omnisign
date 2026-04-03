package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile

/**
 * Returns the absolute filesystem path of a [PlatformFile], or `null` on platforms
 * that do not expose file paths (e.g., Wasm where files come from the browser).
 */
expect fun platformFilePath(file: PlatformFile): String?

