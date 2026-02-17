package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * Token type for certificate storage.
 */
@Serializable
enum class TokenType {
    /**
     * File-based keystore (PKCS12, JKS).
     */
    FILE,
    
    /**
     * Hardware token (PKCS11).
     */
    PKCS11,
    
    /**
     * Windows certificate store.
     */
    WINDOWS_MY,
    
    /**
     * macOS Keychain.
     */
    MACOS_KEYCHAIN
}

