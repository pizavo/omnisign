package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write

/**
 * JVM implementation — opens a native save dialog via FileKit and writes the ZIP archive bytes.
 */
actual suspend fun exportConfigArchive(bytes: ByteArray, suggestedName: String): Boolean {
    val destination = FileKit.openFileSaver(suggestedName = suggestedName, defaultExtension = "zip") ?: return false
    destination.write(bytes)
    return true
}

/**
 * JVM implementation — opens a native open dialog via FileKit and reads the chosen ZIP's bytes.
 */
actual suspend fun importConfigArchive(): ByteArray? =
    FileKit.openFilePicker(type = FileKitType.File(extensions = listOf("zip")))?.readBytes()
