package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS implementation — the native title bar preference is not applicable
 * on the web platform. Always returns `null`.
 */
actual fun loadUseNativeTitleBar(): Boolean? = null

/**
 * Wasm/JS implementation — no-op; the title bar preference is not applicable on web.
 */
actual fun saveUseNativeTitleBar(useNative: Boolean) { }
