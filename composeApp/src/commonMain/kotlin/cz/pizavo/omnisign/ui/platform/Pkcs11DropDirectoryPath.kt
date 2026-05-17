package cz.pizavo.omnisign.ui.platform

/**
 * Returns the absolute path of the platform-appropriate PKCS#11 drop directory,
 * or `null` when the current target has no such concept (e.g. the Wasm browser
 * build).  The directory may not exist yet — see [openInFileExplorer] for the
 * helper that creates it on demand when revealing it to the user.
 *
 * Mirrors `cz.pizavo.omnisign.data.service.pkcs11DropDir` in `shared/jvmMain`
 * but exposed as a stringly-typed value so common UI code can render and link
 * the path without referencing JVM-only types.
 */
expect fun resolvePkcs11DropDirectory(): String?
