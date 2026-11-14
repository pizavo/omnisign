package cz.pizavo.omnisign.data.repository

import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The text of the configuration file at this path, without the byte order mark that some Windows
 * editors, Windows PowerShell 5.1 and older Notepad among them, write at the start of a UTF-8 file.
 *
 * A JSON decoder refuses the mark, so every reader of the configuration file goes through here: a
 * reader that kept the mark would find no configuration in a file the others read and save.
 */
internal fun Path.readConfigText(): String = readText().removePrefix("\uFEFF")
