package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.util.globRootExists
import cz.pizavo.omnisign.data.util.isAbsoluteGlobRoot
import cz.pizavo.omnisign.data.util.absoluteGlobExample as sharedAbsoluteGlobExample
import cz.pizavo.omnisign.data.util.globNeedsFilePattern as sharedGlobNeedsFilePattern
import cz.pizavo.omnisign.data.util.isParseableGlob as sharedIsParseableGlob

/**
 * JVM (desktop) implementations — delegate to the shared filesystem-aware glob helpers, so the
 * desktop and the CLI judge "absolute" identically.
 */
actual fun isAbsoluteGlob(glob: String): Boolean = isAbsoluteGlobRoot(glob)

actual fun absoluteGlobExample(): String = sharedAbsoluteGlobExample()

actual fun globTargetExists(glob: String): Boolean = globRootExists(glob)

actual fun globNeedsFilePattern(glob: String): Boolean = sharedGlobNeedsFilePattern(glob)

actual fun isParseableGlob(glob: String): Boolean = sharedIsParseableGlob(glob)
