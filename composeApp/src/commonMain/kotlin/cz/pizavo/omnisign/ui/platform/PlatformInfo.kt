package cz.pizavo.omnisign.ui.platform

/**
 * Returns `true` when the application is running on a Linux JVM desktop.
 *
 * On Wasm/JS this always returns `false`; on JVM it inspects `os.name`.
 */
expect fun isLinuxPlatform(): Boolean

/**
 * Returns `true` when the application is running on the web (Wasm) target.
 *
 * The web target talks to a remote server whose configuration is read-only over the API
 * (`RemoteConfigRepository.saveConfig` fails), so the UI uses this to present configuration
 * surfaces — such as the profiles panel — in a view-only mode. Always `false` on the JVM
 * desktop, which owns its configuration and can mutate it.
 */
expect fun isWebPlatform(): Boolean

