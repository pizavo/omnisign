package cz.pizavo.omnisign.ui.platform

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

private val logger = LoggerFactory.getLogger("cz.pizavo.omnisign.ui.platform.OpenInFileExplorer")

/**
 * JVM implementation — delegates to [java.awt.Desktop.open], which maps to
 * `explorer.exe`, `open`, and `xdg-open` on Windows, macOS, and Linux
 * respectively.  Best-effort: returns `false` on headless JVMs, when the
 * platform doesn't support the OPEN action, or when the call itself fails.
 *
 * If [path] points to a non-existent directory, this function attempts to create
 * it (parents included) before opening so the canonical PKCS#11 drop directory
 * is reachable even on a fresh install where discovery hasn't run yet.
 */
actual fun openInFileExplorer(path: String): Boolean {
	val target = File(path)
	if (!target.exists()) {
		runCatching { target.mkdirs() }
			.onFailure { logger.debug("Failed to create directory '{}'", path, it) }
	}
	if (!Desktop.isDesktopSupported()) return false
	val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return false
	if (!desktop.isSupported(Desktop.Action.OPEN)) return false
	return runCatching {
		desktop.open(target)
		true
	}.getOrElse {
		logger.debug("Desktop.open('{}') failed", path, it)
		false
	}
}
