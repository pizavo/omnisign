package cz.pizavo.omnisign.ui.platform

/**
 * Returns `true` when the application is running on a Linux JVM desktop.
 *
 * On Wasm/JS this always returns `false`; on JVM it inspects `os.name`.
 */
expect fun isLinuxPlatform(): Boolean

