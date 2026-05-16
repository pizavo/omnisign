package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/JS stub — the browser sandbox has no concept of a local drop directory,
 * so callers fall back to hiding the affordance.
 */
actual fun resolvePkcs11DropDirectory(): String? = null
