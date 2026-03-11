package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType

/**
 * Information about a discovered token.
 *
 * @property id Unique identifier for this token entry.
 * @property name Human-readable display name.
 * @property type Storage technology used by this token.
 * @property path File-system path to the PKCS#11 library or PKCS#12 file; null for OS-native stores.
 * @property requiresPin Whether a PIN/password must be supplied to access the token.
 */
data class TokenInfo(
    val id: String,
    val name: String,
    val type: TokenType,
    val path: String? = null,
    val requiresPin: Boolean = true,
)
