package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Resolved configuration combining global, profile, and operation-specific settings.
 * This is what actually gets used for operations.
 *
 * @property disabledHashAlgorithms The union of all disabled hash algorithms across global,
 *   profile, and operation layers.  Any algorithm in this set must not appear as
 *   [hashAlgorithm]; [resolve] enforces this invariant and returns an error if violated.
 * @property disabledEncryptionAlgorithms The union of all disabled encryption algorithms across
 *   global, profile, and operation layers.  Same enforcement applies to [encryptionAlgorithm].
 */
data class ResolvedConfig(
	val hashAlgorithm: HashAlgorithm,
	val encryptionAlgorithm: EncryptionAlgorithm?,
	val signatureLevel: SignatureLevel,
	val timestampServer: TimestampServerConfig?,
	val ocsp: OcspConfig,
	val crl: CrlConfig,
	val validation: ValidationConfig,
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet()
) {
	companion object {
		/**
		 * Resolve configuration by merging global, profile, and operation-specific settings.
		 * Priority: operation-specific > profile > global.
		 *
		 * Timestamp server settings are merged at the field level so that a per-execution
		 * credential override does not discard the stored server URL, and vice versa.
		 *
		 * When [excludeGlobalTls] is `true`, custom trusted lists from the global config
		 * are excluded from the resolved [ValidationConfig.customTrustedLists].  Profile-
		 * and operation-level TLs are still included.  Useful when a profile defines its
		 * own isolated set of trusted lists and global entries should not interfere.
		 *
		 * Disabled algorithm sets are **union-merged** across layers so that a lower-priority
		 * layer can never re-enable an algorithm that a higher-priority layer has disabled.
		 * If the resolved [hashAlgorithm] or [encryptionAlgorithm] falls in the resulting
		 * disabled set, [Either.Left] with a [ConfigurationError.InvalidConfiguration] is
		 * returned instead of a [ResolvedConfig].
		 */
		fun resolve(
			global: GlobalConfig,
			profile: ProfileConfig?,
			operationOverrides: OperationConfig?,
			excludeGlobalTls: Boolean = false
		): Either<ConfigurationError.InvalidConfiguration, ResolvedConfig> {
			val baseTimestamp = profile?.timestampServer ?: global.timestampServer
			val resolvedTimestamp = mergeTimestampConfig(baseTimestamp, operationOverrides?.timestampServer)
			val resolvedValidation = mergeValidationConfig(
				global.validation,
				profile?.validation,
				operationOverrides?.validation,
				excludeGlobalTls
			)

			val disabledHash = global.disabledHashAlgorithms +
					(profile?.disabledHashAlgorithms ?: emptySet()) +
					(operationOverrides?.disabledHashAlgorithms ?: emptySet())

			val disabledEncryption = global.disabledEncryptionAlgorithms +
					(profile?.disabledEncryptionAlgorithms ?: emptySet()) +
					(operationOverrides?.disabledEncryptionAlgorithms ?: emptySet())

			val resolvedHash = operationOverrides?.hashAlgorithm
				?: profile?.hashAlgorithm
				?: global.defaultHashAlgorithm

			val resolvedEncryption = operationOverrides?.encryptionAlgorithm
				?: profile?.encryptionAlgorithm
				?: global.defaultEncryptionAlgorithm

			if (resolvedHash in disabledHash) {
				return ConfigurationError.InvalidConfiguration(
					message = "Hash algorithm ${resolvedHash.name} is disabled and cannot be used"
				).left()
			}

			if (resolvedEncryption != null && resolvedEncryption in disabledEncryption) {
				return ConfigurationError.InvalidConfiguration(
					message = "Encryption algorithm ${resolvedEncryption.name} is disabled and cannot be used"
				).left()
			}

			return ResolvedConfig(
				hashAlgorithm = resolvedHash,
				encryptionAlgorithm = resolvedEncryption,
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
				validation = resolvedValidation,
				disabledHashAlgorithms = disabledHash,
				disabledEncryptionAlgorithms = disabledEncryption
			).right()
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
		 * [ValidationConfig.algorithmConstraints] is merged field-by-field: each nullable
		 * severity field falls through operation → profile → global → [AlgorithmConstraintsConfig.DEFAULT].
		 * [AlgorithmConstraintsConfig.expirationDateOverrides] is merged additively so that
		 * all three layers contribute overrides, with higher-priority layers winning on key
		 * collisions.
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
			
			val mergedCerts = buildMap<String, TrustedCertificateConfig> {
				if (!excludeGlobalTls) global.trustedCertificates.forEach { put(it.name, it) }
				profile?.trustedCertificates?.forEach { put(it.name, it) }
				operation?.trustedCertificates?.forEach { put(it.name, it) }
			}.values.toList()

			return ValidationConfig(
				policyType = operation?.policyType ?: profile?.policyType ?: global.policyType,
				customPolicyPath = operation?.customPolicyPath ?: profile?.customPolicyPath ?: global.customPolicyPath,
				checkRevocation = operation?.checkRevocation ?: profile?.checkRevocation ?: global.checkRevocation,
				useEuLotl = operation?.useEuLotl ?: profile?.useEuLotl ?: global.useEuLotl,
				customTrustedLists = mergedTls,
				trustedCertificates = mergedCerts,
				algorithmConstraints = mergeAlgorithmConstraints(
					global.algorithmConstraints,
					profile?.algorithmConstraints,
					operation?.algorithmConstraints
				)
			)
		}

		/**
		 * Merge [AlgorithmConstraintsConfig] layers in priority order: operation > profile > global.
		 *
		 * Each nullable severity field falls through the layers until a non-null value is
		 * found, with [AlgorithmConstraintsConfig.DEFAULT] as the terminal fallback so the
		 * result always has non-null severities.
		 *
		 * [AlgorithmConstraintsConfig.policyUpdateDate] uses highest-priority-wins (the most
		 * recently updated layer's stamp takes effect).
		 *
		 * [AlgorithmConstraintsConfig.expirationDateOverrides] is merged additively — global
		 * entries are the base, profile entries override them, operation entries override those.
		 */
		private fun mergeAlgorithmConstraints(
			global: AlgorithmConstraintsConfig,
			profile: AlgorithmConstraintsConfig?,
			operation: AlgorithmConstraintsConfig?
		): AlgorithmConstraintsConfig {
			val defaults = AlgorithmConstraintsConfig.DEFAULT
			return AlgorithmConstraintsConfig(
				expirationLevel = operation?.expirationLevel
					?: profile?.expirationLevel
					?: global.expirationLevel
					?: defaults.expirationLevel,
				expirationLevelAfterUpdate = operation?.expirationLevelAfterUpdate
					?: profile?.expirationLevelAfterUpdate
					?: global.expirationLevelAfterUpdate
					?: defaults.expirationLevelAfterUpdate,
				policyUpdateDate = operation?.policyUpdateDate
					?: profile?.policyUpdateDate
					?: global.policyUpdateDate,
				expirationDateOverrides = global.expirationDateOverrides
						+ (profile?.expirationDateOverrides ?: emptyMap())
						+ (operation?.expirationDateOverrides ?: emptyMap())
			)
		}
	}
}
