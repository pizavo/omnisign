package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS stub — configuration export has no browser backend.
 */
actual suspend fun exportConfigArchive(bytes: ByteArray, suggestedName: String): Boolean = false

/**
 * Wasm/JS stub — configuration import has no browser backend.
 */
actual suspend fun importConfigArchive(): ByteArray? = null
