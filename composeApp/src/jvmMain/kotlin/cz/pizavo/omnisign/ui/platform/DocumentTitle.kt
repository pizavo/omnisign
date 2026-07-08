package cz.pizavo.omnisign.ui.platform

/**
 * JVM / desktop implementation — a no-op. The desktop window title is owned by the `Window` composable,
 * and the desktop target carries no provider branding.
 */
actual fun updateDocumentTitle(title: String) {}
