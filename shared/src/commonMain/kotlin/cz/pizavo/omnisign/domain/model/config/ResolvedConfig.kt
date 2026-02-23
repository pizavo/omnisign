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
         * Priority: operation-specific > profile > global.
         *
         * Timestamp server settings are merged at the field level so that a per-execution
         * credential override does not discard the stored server URL, and vice-versa.
         *
         * When [excludeGlobalTls] is `true`, custom trusted lists from the global config
         * are excluded from the resolved [ValidationConfig.customTrustedLists].  Profile-
         * and operation-level TLs are still included.  Useful when a profile defines its
         * own isolated set of trusted lists and global entries should not interfere.
         */
        fun resolve(
            global: GlobalConfig,
            profile: ProfileConfig?,
            operationOverrides: OperationConfig?,
            excludeGlobalTls: Boolean = false
        ): ResolvedConfig {
            val baseTimestamp = profile?.timestampServer ?: global.timestampServer
            val resolvedTimestamp = mergeTimestampConfig(baseTimestamp, operationOverrides?.timestampServer)
            val resolvedValidation = mergeValidationConfig(
                global.validation,
                profile?.validation,
                operationOverrides?.validation,
                excludeGlobalTls
            )

            return ResolvedConfig(
                hashAlgorithm = operationOverrides?.hashAlgorithm
                    ?: profile?.hashAlgorithm
                    ?: global.defaultHashAlgorithm,
                signatureLevel = operationOverrides?.signatureLevel
                    ?: profile?.signatureLevel
                    ?: global.defaultSignatureLevel,
                timestampServer = resolvedTimestamp,
                ocsp = operationOverrides?.ocsp
                    ?: profile?.ocsp
                    ?: global.ocsp,
                crl = operationOverrides?.crl
                    ?: profile?.crl
                    ?: global.crl,
                validation = resolvedValidation
            )
        }

        /**
         * Merge an operation-level timestamp override onto a base config.
         * Individual fields from [override] take precedence; a blank override URL
         * falls back to the [base] URL so credential-only overrides work correctly.
         * [TimestampServerConfig.runtimePassword] is preserved from the override
         * so in-memory passwords are not silently dropped.
         */
        private fun mergeTimestampConfig(
            base: TimestampServerConfig?,
            override: TimestampServerConfig?
        ): TimestampServerConfig? {
            if (override == null) return base
            if (base == null) return override.takeIf { it.url.isNotBlank() }
            return base.copy(
                url = override.url.ifBlank { base.url },
                username = override.username ?: base.username,
                credentialKey = override.credentialKey ?: base.credentialKey,
                timeout = override.timeout.takeIf { it != 30000 } ?: base.timeout,
                runtimePassword = override.runtimePassword ?: base.runtimePassword
            )
        }

        /**
         * Merge [ValidationConfig] layers in priority order: operation > profile > global.
         *
         * Scalar fields (policy type, revocation flags, LOTL toggle) are resolved with
         * the standard higher-priority-wins rule.  [ValidationConfig.customTrustedLists]
         * is the **union** of all three layers so that global, profile-scoped, and
         * per-operation TLs are all active simultaneously.  When two entries share the
         * same name the higher-priority layer's entry is kept.
         *
         * When [excludeGlobalTls] is `true`, the global layer's
         * [ValidationConfig.customTrustedLists] are omitted from the union so only
         * profile- and operation-level TLs are active.
         */
        private fun mergeValidationConfig(
            global: ValidationConfig,
            profile: ValidationConfig?,
            operation: ValidationConfig?,
            excludeGlobalTls: Boolean = false
        ): ValidationConfig {
            val mergedTls = buildMap<String, CustomTrustedListConfig> {
                if (!excludeGlobalTls) global.customTrustedLists.forEach { put(it.name, it) }
                profile?.customTrustedLists?.forEach { put(it.name, it) }
                operation?.customTrustedLists?.forEach { put(it.name, it) }
            }.values.toList()

            return ValidationConfig(
                policyType = operation?.policyType ?: profile?.policyType ?: global.policyType,
                customPolicyPath = operation?.customPolicyPath ?: profile?.customPolicyPath ?: global.customPolicyPath,
                checkRevocation = operation?.checkRevocation ?: profile?.checkRevocation ?: global.checkRevocation,
                useEuLotl = operation?.useEuLotl ?: profile?.useEuLotl ?: global.useEuLotl,
                customTrustedLists = mergedTls
            )
        }
    }
}


