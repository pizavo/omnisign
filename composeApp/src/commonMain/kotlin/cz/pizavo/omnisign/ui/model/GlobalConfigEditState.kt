package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.*
import cz.pizavo.omnisign.domain.model.config.enums.*
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate

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
 * @property alertIfNotEuLotl Whether the validation UI flags signatures not anchored on the EU LOTL.
 * @property algoExpirationLevel Severity when an algorithm expired before the policy update date.
 * @property algoExpirationLevelAfterUpdate Severity when an algorithm expired after the policy update date.
 * @property customTrustedLists Registered external trusted list sources.
 * @property trustedCertificates Global-scope directly-trusted certificates currently in the
 *   app-managed trust store (the baseline loaded when the dialog opened). Display subtracts
 *   [pendingTrustedCertRemovals] and adds [pendingTrustedCertAdds] on top of this.
 * @property pendingTrustedCertAdds Certificates staged to be added to the global scope on save.
 * @property pendingTrustedCertRemovals Fingerprints of baseline certificates staged for removal on save.
 * @property trustedCertsAvailable Whether the trust store backend is wired in (false on web).
 * @property trustedCertAddError Error from the last failed certificate add attempt, or `null`. Emitted
 *   as locale-agnostic data; the UI resolves it to a message via [TrustedCertAddError.resolve].
 * @property customPkcs11Libraries User-registered PKCS#11 middleware libraries.
 * @property trustedListRefreshInterval Process-global trusted-list refresh interval in hours,
 *   stored as a string for the text field. Clamped to a minimum of 1 hour on save.
 * @property renewalJobs Renewal jobs managed in this edit session.
 * @property availableProfiles Names of profiles available for the renewal job profile dropdown.
 * @property activeProfile The currently active profile name, used as the default selection when adding a renewal job.
 * @property schedulerCliPath Manual fallback path to the OmniSign executable, used only when auto-detection is unavailable.
 * @property schedulerAutoDetectedPath Auto-detected executable path of the running process, or `null` when unavailable.
 *   When present, this path takes precedence over [schedulerCliPath] for scheduler installation and is shown as
 *   read-only info in the UI. The editable [schedulerCliPath] field is hidden.
 * @property schedulerHour Hour of the day (0–23) for the daily scheduler run.
 * @property schedulerMinute Minute (0–59) for the daily scheduler run.
 * @property schedulerLogFile Optional append-only log file path for scheduler output.
 * @property stalenessNotificationEnabled Whether to warn (via OS notification) when renewal has gone
 *   too long without a successful run.
 * @property stalenessThresholdDays Days without a successful run before the staleness warning fires,
 *   stored as a string for the text field. Coerced to at least 1 on save.
 * @property schedulerInstalled Whether the OS scheduler job is currently registered (read-only, queried on the load).
 * @property renewalRunRecord Status of the most recent renewal batch run (read-only, queried on load), or `null` when none has run or no backend is available.
 * @property saving Whether a save operation is currently in progress.
 * @property error Error from the last failed operation, or `null`. Emitted as locale-agnostic data;
 *   the UI resolves it to a message via [SettingsError.resolve].
 * @property tlAddError Human-readable error from the last failed trusted list add attempt, or `null`.
 * @property renewalJobAddError Human-readable error from the last failed renewal job add attempt, or `null`.
 * @property useNativeTitleBar Whether to use the native OS title bar instead of the merged custom toolbar on Linux.
 *   This preference is persisted separately from [GlobalConfig] and requires an application restart to take effect.
 * @property showNativeTitleBarOption Whether the native title bar toggle should be visible in the settings dialog.
 *   Set to `true` only on Linux JVM desktop where the toggle is meaningful.
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
	val alertIfNotEuLotl: Boolean = false,
	val algoExpirationLevel: AlgorithmConstraintLevel = AlgorithmConstraintLevel.FAIL,
	val algoExpirationLevelAfterUpdate: AlgorithmConstraintLevel = AlgorithmConstraintLevel.WARN,
	val customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	val trustedCertificates: List<TrustedCertificate> = emptyList(),
	val pendingTrustedCertAdds: List<PendingTrustedCert> = emptyList(),
	val pendingTrustedCertRemovals: Set<String> = emptySet(),
	val trustedCertsAvailable: Boolean = true,
	val trustedCertAddError: TrustedCertAddError? = null,
	val customPkcs11Libraries: List<CustomPkcs11Library> = emptyList(),
	val pkcs11ProbeTimeout: String = "30",
	val trustedListRefreshInterval: String = "24",
	val renewalJobs: List<RenewalJob> = emptyList(),
	val availableProfiles: List<String> = emptyList(),
	val activeProfile: String? = null,
	val schedulerCliPath: String = "",
	val schedulerAutoDetectedPath: String? = null,
	val schedulerHour: String = "2",
	val schedulerMinute: String = "0",
	val schedulerLogFile: String = "",
	val stalenessNotificationEnabled: Boolean = true,
	val stalenessThresholdDays: String = "14",
	val schedulerInstalled: Boolean = false,
	val renewalRunRecord: RenewalRunRecord? = null,
	val saving: Boolean = false,
	val error: SettingsError? = null,
	val tlAddError: String? = null,
	val renewalJobAddError: String? = null,
	val useNativeTitleBar: Boolean = false,
	val showNativeTitleBarOption: Boolean = false,
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
	 * The executable path that will actually be used for the OS scheduler.
	 *
	 * Prefers [schedulerAutoDetectedPath] when available, falling back to
	 * the manually entered [schedulerCliPath].
	 */
	val effectiveSchedulerExecutablePath: String?
		get() = schedulerAutoDetectedPath ?: schedulerCliPath.trim().ifBlank { null }

	/**
	 * Whether the [pkcs11ProbeTimeout] string represents a valid timeout (1–120 seconds).
	 * Empty strings are treated as valid (defaults are applied on save).
	 */
	val isPkcs11ProbeTimeoutValid: Boolean
		get() = pkcs11ProbeTimeout.isEmpty() || pkcs11ProbeTimeout.toLongOrNull()?.let { it in 1..120 } == true

	/**
	 * Whether the [trustedListRefreshInterval] string represents a valid interval
	 * (a positive whole number of hours). Empty strings are treated as valid
	 * (the default is applied on save).
	 */
	val isTrustedListRefreshIntervalValid: Boolean
		get() = trustedListRefreshInterval.isEmpty() ||
				trustedListRefreshInterval.toLongOrNull()?.let { it >= 1 } == true

	/**
	 * Whether the [schedulerHour] string represents a valid hour (0–23).
	 * Empty strings are treated as valid (defaults are applied on save).
	 */
	val isSchedulerHourValid: Boolean
		get() = schedulerHour.isEmpty() || schedulerHour.toIntOrNull()?.let { it in 0..23 } == true

	/**
	 * Whether the [schedulerMinute] string represents a valid minute (0–59).
	 * Empty strings are treated as valid (defaults are applied on save).
	 */
	val isSchedulerMinuteValid: Boolean
		get() = schedulerMinute.isEmpty() || schedulerMinute.toIntOrNull()?.let { it in 0..59 } == true

	/**
	 * Whether the [stalenessThresholdDays] string represents a valid threshold (a positive whole
	 * number of days). Empty strings are treated as valid (the default is applied on save).
	 */
	val isStalenessThresholdDaysValid: Boolean
		get() = stalenessThresholdDays.isEmpty() || stalenessThresholdDays.toIntOrNull()?.let { it >= 1 } == true

	/**
	 * Whether any scheduler time field contains an out-of-range value.
	 */
	val hasSchedulerTimeError: Boolean
		get() = !isSchedulerHourValid || !isSchedulerMinuteValid

	/**
	 * Compare only the persistable content fields of two states, ignoring
	 * transient UI properties like [saving], [error], and [tlAddError].
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
				alertIfNotEuLotl == other.alertIfNotEuLotl &&
				algoExpirationLevel == other.algoExpirationLevel &&
				algoExpirationLevelAfterUpdate == other.algoExpirationLevelAfterUpdate &&
				customTrustedLists == other.customTrustedLists &&
				pendingTrustedCertAdds == other.pendingTrustedCertAdds &&
				pendingTrustedCertRemovals == other.pendingTrustedCertRemovals &&
				customPkcs11Libraries == other.customPkcs11Libraries &&
				pkcs11ProbeTimeout == other.pkcs11ProbeTimeout &&
				trustedListRefreshInterval == other.trustedListRefreshInterval &&
				renewalJobs == other.renewalJobs &&
				schedulerCliPath == other.schedulerCliPath &&
				schedulerHour == other.schedulerHour &&
				schedulerMinute == other.schedulerMinute &&
				schedulerLogFile == other.schedulerLogFile &&
				stalenessNotificationEnabled == other.stalenessNotificationEnabled &&
				stalenessThresholdDays == other.stalenessThresholdDays &&
				useNativeTitleBar == other.useNativeTitleBar

	/**
	 * Convert this UI state back into a persistable [GlobalConfig].
	 *
	 * The [timestampPassword] is intentionally **not** included in the returned config
	 * because passwords are persisted separately through the OS credential store.
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
			alertIfNotEuLotl = alertIfNotEuLotl,
			customTrustedLists = customTrustedLists,
			algorithmConstraints = AlgorithmConstraintsConfig(
				expirationLevel = algoExpirationLevel,
				expirationLevelAfterUpdate = algoExpirationLevelAfterUpdate,
			),
		),
		customPkcs11Libraries = customPkcs11Libraries,
		pkcs11ProbeTimeoutSeconds = (pkcs11ProbeTimeout.toLongOrNull() ?: 30).coerceIn(1, 120),
		trustedListRefreshIntervalHours = (trustedListRefreshInterval.toLongOrNull() ?: 24).coerceAtLeast(1),
	)

	companion object {

		/**
		 * Build a [GlobalConfigEditState] from an existing [GlobalConfig].
		 *
		 * @param config The source global configuration.
		 * @param hasStoredPassword Whether a TSA password is already persisted in the credential store.
		 * @param renewalJobs Current renewal jobs from [cz.pizavo.omnisign.domain.model.config.AppConfig.renewalJobs].
		 * @param availableProfiles Profile names available for the renewal job profile dropdown.
		 * @param activeProfile The currently active profile name, or `null` if none is active.
		 * @param schedulerConfig Persisted scheduler settings.
		 * @param schedulerAutoDetectedPath Auto-detected executable path, or `null` when unavailable.
		 * @param trustedCertificates Global-scope certificates currently in the app-managed trust store.
		 * @return A new edit state pre-populated with the config's values.
		 */
		fun from(
			config: GlobalConfig,
			hasStoredPassword: Boolean = false,
			renewalJobs: Map<String, RenewalJob> = emptyMap(),
			availableProfiles: List<String> = emptyList(),
			activeProfile: String? = null,
			schedulerConfig: SchedulerConfig = SchedulerConfig(),
			schedulerInstalled: Boolean = false,
			schedulerAutoDetectedPath: String? = null,
			trustedCertificates: List<TrustedCertificate> = emptyList(),
		): GlobalConfigEditState {
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
				alertIfNotEuLotl = config.validation.alertIfNotEuLotl ?: false,
				algoExpirationLevel = config.validation.algorithmConstraints.expirationLevel
					?: AlgorithmConstraintLevel.FAIL,
				algoExpirationLevelAfterUpdate = config.validation.algorithmConstraints.expirationLevelAfterUpdate
					?: AlgorithmConstraintLevel.WARN,
				customTrustedLists = config.validation.customTrustedLists,
				trustedCertificates = trustedCertificates,
				customPkcs11Libraries = config.customPkcs11Libraries,
				pkcs11ProbeTimeout = config.pkcs11ProbeTimeoutSeconds.toString(),
				trustedListRefreshInterval = config.trustedListRefreshIntervalHours.toString(),
				renewalJobs = renewalJobs.values.toList(),
				availableProfiles = availableProfiles,
				activeProfile = activeProfile,
				schedulerCliPath = schedulerConfig.cliExecutablePath.orEmpty(),
				schedulerAutoDetectedPath = schedulerAutoDetectedPath,
				schedulerHour = schedulerConfig.runAtHour.toString(),
				schedulerMinute = schedulerConfig.runAtMinute.toString(),
				schedulerLogFile = schedulerConfig.logFilePath.orEmpty(),
				stalenessNotificationEnabled = schedulerConfig.stalenessNotificationEnabled,
				stalenessThresholdDays = schedulerConfig.stalenessThresholdDays.toString(),
				schedulerInstalled = schedulerInstalled,
			)
		}
	}
}
