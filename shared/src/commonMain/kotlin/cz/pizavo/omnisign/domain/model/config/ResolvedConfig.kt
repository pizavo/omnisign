package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
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
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	/**
	 * Name of the profile this config was resolved for, or `null` for a global-only resolution.
	 * Carried so trust resolution can select the matching
	 * [cz.pizavo.omnisign.domain.model.trust.TrustScope].
	 */
	val profileName: String? = null,
) {
	companion object {
		/**
		 * Resolve configuration by merging global, profile, and operation-specific settings.
		 * Priority: operation-specific > profile > global.
		 *
		 * Timestamp server settings are merged at the field level so that a per-execution
		 * credential override does not discard the stored server URL, and vice versa. A URL
		 * override that points at another server inherits none of the stored credentials.
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
				val selectedIn = hashSource(operationOverrides, profile)
				val disabledIn = hashDisabledLayers(resolvedHash, global, profile, operationOverrides)
				return ConfigurationError.InvalidConfiguration(
					LocalizableText.Literal(
						"Hash algorithm ${resolvedHash.name} is selected in $selectedIn but disabled in $disabledIn"
					)
				).left()
			}

			if (resolvedEncryption != null && resolvedEncryption in disabledEncryption) {
				val selectedIn = encryptionSource(operationOverrides, profile)
				val disabledIn = encryptionDisabledLayers(resolvedEncryption, global, profile, operationOverrides)
				return ConfigurationError.InvalidConfiguration(
					LocalizableText.Literal(
						"Encryption algorithm ${resolvedEncryption.name} is selected in $selectedIn but disabled in $disabledIn"
					)
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
				disabledEncryptionAlgorithms = disabledEncryption,
				profileName = profile?.name,
			).right()
		}

		/**
		 * Merge an operation-level timestamp [override] onto the stored [base] config.
		 * A field the override sets takes precedence and a field it leaves `null` keeps the
		 * [base] value: an override without a URL keeps the stored server, one without a timeout
		 * keeps the stored timeout, and a timeout it gives replaces the stored one even when it
		 * equals [TimestampServerConfig.DEFAULT_TIMEOUT_MS]. The override's in-memory password
		 * is kept so that a password given for this operation is not silently dropped.
		 *
		 * The credentials of [base], its username, credential key and in-memory password, carry
		 * over only while the override stays with the [base] server: its URL is blank or names the
		 * same server ([sameServer]). An override that points at another server takes only the
		 * credentials given with it, so a password stored for one server is never sent to another.
		 * Without a [base], an override that names no server resolves to none.
		 */
		private fun mergeTimestampConfig(
			base: TimestampServerConfig?,
			override: TimestampServerOverride?
		): TimestampServerConfig? {
			if (override == null) return base
			val url = override.url?.takeIf { it.isNotBlank() }
			if (base != null && (url == null || sameServer(url, base.url))) {
				return base.copy(
					url = url ?: base.url,
					username = override.username ?: base.username,
					timeout = override.timeout ?: base.timeout,
					runtimePassword = override.runtimePassword ?: base.runtimePassword
				)
			}
			return url?.let {
				TimestampServerConfig(
					url = it,
					username = override.username,
					timeout = override.timeout ?: base?.timeout ?: TimestampServerConfig.DEFAULT_TIMEOUT_MS,
					runtimePassword = override.runtimePassword
				)
			}
		}

		/**
		 * Whether the URLs [first] and [second] point at the same server: they are equal, or
		 * [originOf] reads the same origin from both. A URL it cannot read matches no other.
		 */
		private fun sameServer(first: String, second: String): Boolean =
			first == second || originOf(first)?.let { it == originOf(second) } == true

		/**
		 * The origin of [url], its scheme, host and port, as `scheme://host:port` in lower case,
		 * with the default port of `http` or `https` when [url] gives none. `null` when [url] is
		 * not of that plain form: user information outside the characters RFC 3986 allows, so
		 * that it cannot hide a second `@` or a backslash; a host other than a name of letters,
		 * digits, dots and hyphens or a bracketed IPv6 address; or no port and a scheme whose
		 * default is not known.
		 */
		private fun originOf(url: String): String? {
			val (scheme, host, port) = ORIGIN_PATTERN.matchEntire(url)?.destructured ?: return null
			val lowerScheme = scheme.lowercase()
			val portNumber = port.toIntOrNull() ?: DEFAULT_PORTS[lowerScheme] ?: return null
			return "$lowerScheme://${host.lowercase()}:$portNumber"
		}

		/**
		 * Merge [ValidationConfig] layers in priority order: operation > profile > global.
		 *
		 * Scalar fields (policy type, revocation flags, LOTL toggle) are resolved with
		 * the standard higher-priority-wins rule: a layer that leaves one unset (`null`)
		 * inherits it from the layer below. The policy type, the revocation check and the
		 * EU LOTL toggle fall back to [ValidationPolicyType.DEFAULT_ETSI], `true` and `true`
		 * when every layer leaves them unset.  [ValidationConfig.customTrustedLists]
		 * is the **union** of all three layers so that global, profile-scoped, and
		 * per-operation TLs are all active simultaneously.  When two entries share the
		 * same name, the higher-priority layer's entry is kept.
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

			return ValidationConfig(
				policyType = operation?.policyType ?: profile?.policyType ?: global.policyType
					?: ValidationPolicyType.DEFAULT_ETSI,
				customPolicyPath = operation?.customPolicyPath ?: profile?.customPolicyPath ?: global.customPolicyPath,
				checkRevocation = operation?.checkRevocation ?: profile?.checkRevocation ?: global.checkRevocation
					?: true,
				allowExpiredCertificate = operation?.allowExpiredCertificate
					?: profile?.allowExpiredCertificate
					?: global.allowExpiredCertificate,
				useEuLotl = operation?.useEuLotl ?: profile?.useEuLotl ?: global.useEuLotl ?: true,
				alertIfNotEuLotl = operation?.alertIfNotEuLotl ?: profile?.alertIfNotEuLotl ?: global.alertIfNotEuLotl,
				customTrustedLists = mergedTls,
				algorithmConstraints = mergeAlgorithmConstraints(
					global.algorithmConstraints,
					profile?.algorithmConstraints,
					operation?.algorithmConstraints
				)
			)
		}

		private const val LAYER_GLOBAL = "global config"
		private const val LAYER_PROFILE = "profile config"
		private const val LAYER_OPERATION = "operation overrides"

		/**
		 * Identify the configuration layer that provided the resolved hash algorithm.
		 */
		private fun hashSource(
			operation: OperationConfig?,
			profile: ProfileConfig?
		): String = when {
			operation?.hashAlgorithm != null -> LAYER_OPERATION
			profile?.hashAlgorithm != null -> LAYER_PROFILE
			else -> LAYER_GLOBAL
		}

		/**
		 * Identify the configuration layer that provided the resolved encryption algorithm.
		 */
		private fun encryptionSource(
			operation: OperationConfig?,
			profile: ProfileConfig?
		): String = when {
			operation?.encryptionAlgorithm != null -> LAYER_OPERATION
			profile?.encryptionAlgorithm != null -> LAYER_PROFILE
			else -> LAYER_GLOBAL
		}

		/**
		 * List the configuration layers that contain [algorithm] in their disabled hash set,
		 * joined with "and" for human-readable error messages.
		 */
		private fun hashDisabledLayers(
			algorithm: HashAlgorithm,
			global: GlobalConfig,
			profile: ProfileConfig?,
			operation: OperationConfig?
		): String = buildList {
			if (algorithm in global.disabledHashAlgorithms) add(LAYER_GLOBAL)
			if (profile != null && algorithm in profile.disabledHashAlgorithms) add(LAYER_PROFILE)
			if (operation != null && algorithm in operation.disabledHashAlgorithms) add(LAYER_OPERATION)
		}.joinToString(" and ")

		/**
		 * List the configuration layers that contain [algorithm] in their disabled encryption set,
		 * joined with "and" for human-readable error messages.
		 */
		private fun encryptionDisabledLayers(
			algorithm: EncryptionAlgorithm,
			global: GlobalConfig,
			profile: ProfileConfig?,
			operation: OperationConfig?
		): String = buildList {
			if (algorithm in global.disabledEncryptionAlgorithms) add(LAYER_GLOBAL)
			if (profile != null && algorithm in profile.disabledEncryptionAlgorithms) add(LAYER_PROFILE)
			if (operation != null && algorithm in operation.disabledEncryptionAlgorithms) add(LAYER_OPERATION)
		}.joinToString(" and ")

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

		/**
		 * A URL's scheme, optional user information, host and optional port, followed by nothing or
		 * by a path, query or fragment. [originOf] reads the scheme, host and port from its groups.
		 */
		private val ORIGIN_PATTERN = Regex(
			"""([A-Za-z][A-Za-z0-9+.-]*)://(?:[A-Za-z0-9._~%!$&'()*+,;=:-]*@)?([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::([0-9]{1,5}))?(?:[/?#].*)?"""
		)

		/** The port a URL of each scheme [originOf] knows connects to when it names none. */
		private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
	}
}
