package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.*

/**
 * Resolved configuration combining global, profile, and operation-specific settings.
 * This is what actually gets used for operations.
 */
data class ResolvedConfig(
    val hashAlgorithm: HashAlgorithm,
    val signatureLevel: SignatureLevel,
    val timestampServer: TimestampServerConfig?,
    val ocsp: OcspConfig,
    val crl: CrlConfig,
    val validation: ValidationConfig
) {
    companion object {
        /**
         * Resolve configuration by merging global, profile, and operation-specific settings.
         * Priority: operation-specific > profile > global
         */
        fun resolve(
            global: GlobalConfig,
            profile: ProfileConfig?,
            operationOverrides: OperationConfig?
        ): ResolvedConfig {
            return ResolvedConfig(
                hashAlgorithm = operationOverrides?.hashAlgorithm
                    ?: profile?.hashAlgorithm
                    ?: global.defaultHashAlgorithm,
                signatureLevel = operationOverrides?.signatureLevel
                    ?: profile?.signatureLevel
                    ?: global.defaultSignatureLevel,
                timestampServer = operationOverrides?.timestampServer
                    ?: profile?.timestampServer
                    ?: global.timestampServer,
                ocsp = operationOverrides?.ocsp
                    ?: profile?.ocsp
                    ?: global.ocsp,
                crl = operationOverrides?.crl
                    ?: profile?.crl
                    ?: global.crl,
                validation = operationOverrides?.validation
                    ?: profile?.validation
                    ?: global.validation
            )
        }
    }
}


