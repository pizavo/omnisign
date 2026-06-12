package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS actual — the browser does not let a web app pick an arbitrary save location, so there
 * is no native dialog. Returns the suggested file name so the caller's [writeBytesToPath] (which
 * on the web hands the bytes to FileKit's download flow) saves the file under that name.
 */
actual suspend fun chooseSaveDestination(
	suggestedName: String,
	extension: String,
	initialDirectory: String?,
	allowedExtensions: Set<String>,
): String? = "$suggestedName.$extension"
