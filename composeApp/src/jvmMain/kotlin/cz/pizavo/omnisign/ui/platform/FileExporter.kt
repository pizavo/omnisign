package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write

/**
 * JVM implementation — opens a native save-file dialog via FileKit and writes the text
 * to the chosen destination.
 */
actual suspend fun exportTextToFile(text: String, suggestedName: String, extension: String) {
    val dest = FileKit.openFileSaver(
        suggestedName = suggestedName,
        extension = extension,
    ) ?: return
    dest.write(text.encodeToByteArray())
}

