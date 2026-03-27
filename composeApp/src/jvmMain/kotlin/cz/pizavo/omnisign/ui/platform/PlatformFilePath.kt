package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath

/**
 * JVM implementation — delegates to the FileKit `absolutePath()` extension function.
 */
actual fun platformFilePath(file: PlatformFile): String? = file.absolutePath()
