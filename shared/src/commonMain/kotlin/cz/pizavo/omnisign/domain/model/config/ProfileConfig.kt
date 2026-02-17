package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.*
import kotlinx.serialization.Serializable

/**
 * Profile-specific configuration.
 * Each field can override the corresponding global setting.
 */
@Serializable
data class ProfileConfig(
    /**
     * Profile name.
     */
    val name: String,
    
    /**
     * Profile description.
     */
    val description: String? = null,
    
    /**
     * Override default hash algorithm.
     */
    val hashAlgorithm: HashAlgorithm? = null,
    
    /**
     * Override default signature level.
     */
    val signatureLevel: SignatureLevel? = null,
    
    /**
     * Override timestamp server configuration.
     */
    val timestampServer: TimestampServerConfig? = null,
    
    /**
     * Override OCSP configuration.
     */
    val ocsp: OcspConfig? = null,
    
    /**
     * Override CRL configuration.
     */
    val crl: CrlConfig? = null,
    
    /**
     * Override validation configuration.
     */
    val validation: ValidationConfig? = null
)


