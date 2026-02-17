package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.*
import kotlinx.serialization.Serializable

/**
 * Global configuration settings.
 */
@Serializable
data class GlobalConfig(
    /**
     * Default hash algorithm for signing.
     */
    val defaultHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
    
    /**
     * Default signature level.
     */
    val defaultSignatureLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
    
    /**
     * Timestamp server configuration.
     */
    val timestampServer: TimestampServerConfig? = null,
    
    /**
     * OCSP configuration.
     */
    val ocsp: OcspConfig = OcspConfig(),
    
    /**
     * CRL configuration.
     */
    val crl: CrlConfig = CrlConfig(),
    
    /**
     * Validation policy settings.
     */
    val validation: ValidationConfig = ValidationConfig()
)


