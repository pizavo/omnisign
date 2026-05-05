package cz.pizavo.omnisign.ui.platform

/**
 * JVM implementation — returns `true` when the host OS is Linux (or another
 * non-Windows, non-macOS system).
 */
actual fun isLinuxPlatform(): Boolean =
	System.getProperty("os.name").lowercase().let { !it.contains("win") && !it.contains("mac") }

