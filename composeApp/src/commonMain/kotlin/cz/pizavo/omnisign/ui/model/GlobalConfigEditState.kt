package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig

/**
 * Mutable-friendly UI state for the global configuration edit dialog.
 *
 * All fields mirror [GlobalConfig] but use primitive/nullable types suitable
 * for two-way data binding with Compose text fields and selectors. Nested
 * configs ([OcspConfig], [CrlConfig], [ValidationConfig]) are flattened into
 * top-level properties so that each form control maps to a single field.
 *
 * @property defaultHashAlgorithm Default hash algorithm for signing.
 * @property defaultEncryptionAlgorithm Default encryption algorithm, or `null` for auto-detect.
 * @property addSignatureTimestamp Whether the default level includes a signature timestamp and revocation data (B-LT).
 * @property addArchivalTimestamp Whether the default level includes an archival document timestamp (B-LTA).
 * @property disabledHashAlgorithms Hash algorithms disabled globally.
 * @property disabledEncryptionAlgorithms Encryption algorithms disabled globally.
 * @property timestampEnabled Whether the timestamp server section is active.
 * @property timestampUrl TSA endpoint URL.
 * @property timestampUsername HTTP Basic auth username for the TSA.
 * @property timestampPassword HTTP Basic auth password for the current edit session.
 * @property hasStoredPassword Whether a password is already persisted in the OS credential store.
 * @property timestampTimeout TSA request timeout in milliseconds, stored as a string for the text field.
 * @property ocspTimeout OCSP request timeout in milliseconds, stored as a string.
 * @property crlTimeout CRL request timeout in milliseconds, stored as a string.
 * @property validationPolicyType Validation policy source.
 * @property customPolicyPath Path to a custom validation policy file.
 * @property checkRevocation Whether to check certificate revocation status.
 * @property useEuLotl Whether to use the EU List of Trusted Lists.
 * @property algoExpirationLevel Severity when an algorithm expired before the policy update date.
 * @property algoExpirationLevelAfterUpdate Severity when an algorithm expired after the policy update date.
 * @property customTrustedLists Registered external trusted list sources.
 * @property trustedCertificates Directly trusted certificates.
 * @property customPkcs11Libraries User-registered PKCS#11 middleware libraries.
 * @property saving Whether a save operation is currently in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 * @property certAddError Human-readable error from the last failed trusted certificate add attempt, or `null`.
 * @property tlAddError Human-readable error from the last failed trusted list add attempt, or `null`.
 */
data class GlobalConfigEditState(
	val defaultHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
	val defaultEncryptionAlgorithm: EncryptionAlgorithm? = null,
	val addSignatureTimestamp: Boolean = false,
	val addArchivalTimestamp: Boolean = false,
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	val timestampEnabled: Boolean = false,
	val timestampUrl: String = "",
	val timestampUsername: String = "",
	val timestampPassword: String = "",
	val hasStoredPassword: Boolean = false,
	val timestampTimeout: String = "30000",
	val ocspTimeout: String = "30000",
	val crlTimeout: String = "30000",
	val validationPolicyType: ValidationPolicyType = ValidationPolicyType.DEFAULT_ETSI,
	val customPolicyPath: String = "",
	val checkRevocation: Boolean = true,
	val useEuLotl: Boolean = true,
	val algoExpirationLevel: AlgorithmConstraintLevel = AlgorithmConstraintLevel.FAIL,
	val algoExpirationLevelAfterUpdate: AlgorithmConstraintLevel = AlgorithmConstraintLevel.WARN,
	val customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	val trustedCertificates: List<TrustedCertificateConfig> = emptyList(),
	val customPkcs11Libraries: List<CustomPkcs11Library> = emptyList(),
	val saving: Boolean = false,
	val error: String? = null,
	val certAddError: String? = null,
	val tlAddError: String? = null,
) {

	/**
	 * Derive the PAdES [SignatureLevel] from the current checkbox state.
	 *
	 * - Both timestamps → B-LTA
	 * - Signature timestamp only → B-LT
	 * - Neither → B-B
	 */
	val effectiveSignatureLevel: SignatureLevel
		get() = when {
			addArchivalTimestamp -> SignatureLevel.PADES_BASELINE_LTA
			addSignatureTimestamp -> SignatureLevel.PADES_BASELINE_LT
			else -> SignatureLevel.PADES_BASELINE_B
		}

	/**
	 * Compare only the persistable content fields of two states, ignoring
	 * transient UI properties like [saving], [error], [certAddError], and [tlAddError].
	 */
	fun contentEquals(other: GlobalConfigEditState): Boolean =
		defaultHashAlgorithm == other.defaultHashAlgorithm &&
				defaultEncryptionAlgorithm == other.defaultEncryptionAlgorithm &&
				addSignatureTimestamp == other.addSignatureTimestamp &&
				addArchivalTimestamp == other.addArchivalTimestamp &&
				disabledHashAlgorithms == other.disabledHashAlgorithms &&
				disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms &&
				timestampEnabled == other.timestampEnabled &&
				timestampUrl == other.timestampUrl &&
				timestampUsername == other.timestampUsername &&
				timestampPassword == other.timestampPassword &&
				hasStoredPassword == other.hasStoredPassword &&
				timestampTimeout == other.timestampTimeout &&
				ocspTimeout == other.ocspTimeout &&
				crlTimeout == other.crlTimeout &&
				validationPolicyType == other.validationPolicyType &&
				customPolicyPath == other.customPolicyPath &&
				checkRevocation == other.checkRevocation &&
				useEuLotl == other.useEuLotl &&
				algoExpirationLevel == other.algoExpirationLevel &&
				algoExpirationLevelAfterUpdate == other.algoExpirationLevelAfterUpdate &&
				customTrustedLists == other.customTrustedLists &&
				trustedCertificates == other.trustedCertificates &&
				customPkcs11Libraries == other.customPkcs11Libraries

	/**
	 * Convert this UI state back into a persistable [GlobalConfig].
	 *
	 * The [timestampPassword] is intentionally **not** included in the returned config
	 * because passwords are persisted separately through the OS credential store.
	 * The [TimestampServerConfig.credentialKey] is set to the username when a password
	 * has been entered or was already stored.
	 */
	fun toGlobalConfig(): GlobalConfig = GlobalConfig(
		defaultHashAlgorithm = defaultHashAlgorithm,
		defaultEncryptionAlgorithm = defaultEncryptionAlgorithm,
		defaultSignatureLevel = effectiveSignatureLevel,
		disabledHashAlgorithms = disabledHashAlgorithms,
		disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
		timestampServer = if (timestampEnabled && timestampUrl.isNotBlank()) {
			val effectiveUsername = timestampUsername.ifBlank { null }
			val hasPassword = timestampPassword.isNotEmpty() || hasStoredPassword
			TimestampServerConfig(
				url = timestampUrl.trim(),
				username = effectiveUsername,
				credentialKey = if (hasPassword && effectiveUsername != null) effectiveUsername else null,
				timeout = timestampTimeout.toIntOrNull() ?: 30000,
			)
		} else {
			null
		},
		ocsp = OcspConfig(timeout = ocspTimeout.toIntOrNull() ?: 30000),
		crl = CrlConfig(timeout = crlTimeout.toIntOrNull() ?: 30000),
		validation = ValidationConfig(
			policyType = validationPolicyType,
			customPolicyPath = customPolicyPath.ifBlank { null },
			checkRevocation = checkRevocation,
			useEuLotl = useEuLotl,
			customTrustedLists = customTrustedLists,
			trustedCertificates = trustedCertificates,
			algorithmConstraints = AlgorithmConstraintsConfig(
				expirationLevel = algoExpirationLevel,
				expirationLevelAfterUpdate = algoExpirationLevelAfterUpdate,
			),
		),
		customPkcs11Libraries = customPkcs11Libraries,
	)

	companion object {

		/**
		 * Build a [GlobalConfigEditState] from an existing [GlobalConfig].
		 *
		 * @param config The source global configuration.
		 * @param hasStoredPassword Whether a TSA password is already persisted in the credential store.
		 * @return A new edit state pre-populated with the config's values.
		 */
		fun from(config: GlobalConfig, hasStoredPassword: Boolean = false): GlobalConfigEditState {
			val level = config.defaultSignatureLevel
			return GlobalConfigEditState(
				defaultHashAlgorithm = config.defaultHashAlgorithm,
				defaultEncryptionAlgorithm = config.defaultEncryptionAlgorithm,
				addSignatureTimestamp = level == SignatureLevel.PADES_BASELINE_LT ||
						level == SignatureLevel.PADES_BASELINE_LTA,
				addArchivalTimestamp = level == SignatureLevel.PADES_BASELINE_LTA,
				disabledHashAlgorithms = config.disabledHashAlgorithms,
				disabledEncryptionAlgorithms = config.disabledEncryptionAlgorithms,
				timestampEnabled = config.timestampServer != null,
				timestampUrl = config.timestampServer?.url.orEmpty(),
				timestampUsername = config.timestampServer?.username.orEmpty(),
				timestampPassword = "",
				hasStoredPassword = hasStoredPassword,
				timestampTimeout = (config.timestampServer?.timeout ?: 30000).toString(),
				ocspTimeout = config.ocsp.timeout.toString(),
				crlTimeout = config.crl.timeout.toString(),
				validationPolicyType = config.validation.policyType,
				customPolicyPath = config.validation.customPolicyPath.orEmpty(),
				checkRevocation = config.validation.checkRevocation,
				useEuLotl = config.validation.useEuLotl,
				algoExpirationLevel = config.validation.algorithmConstraints.expirationLevel
					?: AlgorithmConstraintLevel.FAIL,
				algoExpirationLevelAfterUpdate = config.validation.algorithmConstraints.expirationLevelAfterUpdate
					?: AlgorithmConstraintLevel.WARN,
				customTrustedLists = config.validation.customTrustedLists,
				trustedCertificates = config.validation.trustedCertificates,
				customPkcs11Libraries = config.customPkcs11Libraries,
			)
		}
	}
}
