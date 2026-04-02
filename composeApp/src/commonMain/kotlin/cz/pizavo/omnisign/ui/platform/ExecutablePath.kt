package cz.pizavo.omnisign.ui.platform

/**
 * Attempts to resolve the absolute path of the currently running executable.
 *
 * On JVM, returns the native launcher path when launched via jpackage; `null`
 * when running via `java -jar`. On non-JVM platforms, always returns `null`.
 */
expect fun resolveExecutablePath(): String?

