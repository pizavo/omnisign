package cz.pizavo.omnisign.ui.platform

import java.io.File

/**
 * JVM actual that writes [bytes] to disk at [path], creating parent directories as needed.
 *
 * Returns the exception message on failure so the calling ViewModel can surface it in its
 * error state. Catches all [Throwable]s defensively — disk-write failures (permission, full
 * volume, locked file) should never crash the signing flow.
 */
actual suspend fun writeBytesToPath(path: String, bytes: ByteArray): String? {
    return try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        null
    } catch (e: Throwable) {
        e.message ?: e::class.simpleName ?: "Unknown error"
    }
}
