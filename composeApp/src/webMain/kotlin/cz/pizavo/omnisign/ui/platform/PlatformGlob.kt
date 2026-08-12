package cz.pizavo.omnisign.ui.platform

/**
 * Web stubs — the browser has no filesystem, so renewal globs are not run here. A string heuristic
 * (leading `/`, a drive letter, or a UNC prefix) keeps the shared form usable.
 */
actual fun isAbsoluteGlob(glob: String): Boolean {
	val wildcardIndex = glob.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
	val root = if (wildcardIndex == -1) glob else glob.substring(0, wildcardIndex)
	return root.startsWith("/") ||
		root.startsWith("\\\\") ||
		Regex("^[A-Za-z]:[\\\\/].*").matches(root)
}

actual fun absoluteGlobExample(): String = "/srv/docs/**/*.pdf"

actual fun globTargetExists(glob: String): Boolean = true

actual fun globNeedsFilePattern(glob: String): Boolean = false

actual fun isParseableGlob(glob: String): Boolean = true
