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
 */
@Serializable
data class CustomPkcs11Library(
    val name: String,
    val path: String,
)

