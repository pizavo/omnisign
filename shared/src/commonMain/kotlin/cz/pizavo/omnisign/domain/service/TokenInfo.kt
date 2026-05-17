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
 * @property pkcs11SlotId For PKCS#11 tokens, the slot identifier that hosts the token at
 *   discovery time, as reported by `C_GetSlotList(tokenPresent=CK_TRUE)`.  Used to pin the
 *   SunPKCS11 provider to the correct slot when constructing `Pkcs11SignatureToken`; without
 *   it, DSS defaults to slot 0, which fails on aggregator modules such as p11-kit-proxy where
 *   slot 0 is typically not the user-PIN-protected slot.  Null for non-PKCS#11 tokens.
 */
data class TokenInfo(
    val id: String,
    val name: String,
    val type: TokenType,
    val path: String? = null,
    val requiresPin: Boolean = true,
    val pkcs11SlotId: Long? = null,
)
