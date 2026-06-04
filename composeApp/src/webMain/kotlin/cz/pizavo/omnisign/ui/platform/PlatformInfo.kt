package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS implementation — the web platform is never Linux desktop.
 */
actual fun isLinuxPlatform(): Boolean = false

/**
 * Wasm/JS implementation — this is the web target.
 */
actual fun isWebPlatform(): Boolean = true

