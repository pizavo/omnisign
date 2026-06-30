package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * A user-registered PKCS#11 middleware library entry.
 *
 * Entries are persisted in [GlobalConfig.customPkcs11Libraries] and merged into the
 * token discovery process alongside the OS-native autodiscovery results and the built-in
 * fallback candidate list.
 *
 * @property name Human-readable label shown in the UI and token selection prompts.
 * @property path Absolute path to the PKCS#11 shared library (`.dll`, `.so`, or `.dylib`).
 * @property protectedAuthenticationPath Whether this middleware collects the PIN on its own
 *   secure entry (a hardware pin-pad or the driver's on-screen "virtual keyboard"). When `true`,
 *   OmniSign does **not** show its own PIN dialog for this library — it lets `C_Login` drive the
 *   module's protected authentication path (SunPKCS11 detects `CKF_PROTECTED_AUTHENTICATION_PATH`
 *   and supplies no PIN), avoiding a redundant prompt in front of the module's own pad. Leave
 *   `false` (the default) for middleware that accepts the PIN programmatically.
 */
@Serializable
data class CustomPkcs11Library(
    val name: String,
    val path: String,
    val protectedAuthenticationPath: Boolean = false,
)

