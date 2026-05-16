package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.service.pkcs11DropDir

/**
 * JVM implementation — delegates to the shared
 * [cz.pizavo.omnisign.data.service.pkcs11DropDir] resolver so every consumer
 * (token service, discovery, settings UI) agrees on the same location.
 */
actual fun resolvePkcs11DropDirectory(): String? = pkcs11DropDir().absolutePath
