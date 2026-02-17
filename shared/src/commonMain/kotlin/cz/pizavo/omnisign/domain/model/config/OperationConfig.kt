package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.*

/**
 * Operation-specific configuration overrides.
 * Used to override settings for a single operation.
 */
data class OperationConfig(
    val hashAlgorithm: HashAlgorithm? = null,
    val signatureLevel: SignatureLevel? = null,
    val timestampServer: TimestampServerConfig? = null,
    val ocsp: OcspConfig? = null,
    val crl: CrlConfig? = null,
    val validation: ValidationConfig? = null
)


