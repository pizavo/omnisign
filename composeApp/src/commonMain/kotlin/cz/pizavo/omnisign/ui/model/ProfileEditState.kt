package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.lumo.components.TriToggleState

/**
 * Mutable-friendly UI state for the profile edit form.
 *
 * All fields mirror [ProfileConfig] but use primitive/nullable types suitable
 * for two-way data binding with Compose text fields and selectors.
 *
 * @property profileName The immutable profile name (displayed as a header, not editable).
 * @property description Free-text description of the profile.
 * @property hashAlgorithm Selected hash algorithm override, or `null` to inherit from global.
 * @property encryptionAlgorithm Selected encryption algorithm override, or `null` to inherit.
 * @property signatureTimestampOverride Tri-state toggle for the signature timestamp (B-LT) override.
 * @property archivalTimestampOverride Tri-state toggle for the archival timestamp (B-LTA) override.
 * @property timestampEnabled Whether the timestamp server section is enabled.
 * @property timestampUrl TSA endpoint URL.
 * @property timestampUsername HTTP Basic auth username for the TSA.
 * @property timestampPassword HTTP Basic auth password for the current edit session.
 * @property hasStoredPassword Whether a password is already persisted in the OS credential store.
 * @property timestampTimeout HTTP request timeout in milliseconds, stored as a string for the text field.
 * @property disabledHashAlgorithms Hash algorithms disabled by this profile.
 * @property disabledEncryptionAlgorithms Encryption algorithms disabled by this profile.
 * @property trustedCertificates Directly trusted certificates scoped to this profile.
 * @property saving Whether a save operation is currently in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 * @property certAddError Human-readable error from the last failed trusted certificate add attempt, or `null`.
 */
data class ProfileEditState(
	val profileName: String,
	val description: String = "",
	val hashAlgorithm: HashAlgorithm? = null,
	val encryptionAlgorithm: EncryptionAlgorithm? = null,
	val signatureTimestampOverride: TriToggleState = TriToggleState.INHERIT,
	val archivalTimestampOverride: TriToggleState = TriToggleState.INHERIT,
	val timestampEnabled: Boolean = false,
	val timestampUrl: String = "",
	val timestampUsername: String = "",
	val timestampPassword: String = "",
	val hasStoredPassword: Boolean = false,
	val timestampTimeout: String = "30000",
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	val trustedCertificates: List<TrustedCertificateConfig> = emptyList(),
	val saving: Boolean = false,
	val error: String? = null,
	val certAddError: String? = null,
) {

	/**
	 * Derive the nullable PAdES [SignatureLevel] override from the two toggle states.
	 *
	 * Returns `null` when both toggles are [TriToggleState.INHERIT], meaning no
	 * profile-level override is applied.
	 */
	val effectiveSignatureLevel: SignatureLevel?
		get() {
			if (signatureTimestampOverride == TriToggleState.INHERIT &&
				archivalTimestampOverride == TriToggleState.INHERIT
			) return null

			val archival = archivalTimestampOverride == TriToggleState.ENABLED
			val signature = signatureTimestampOverride == TriToggleState.ENABLED || archival

			return when {
				archival -> SignatureLevel.PADES_BASELINE_LTA
				signature -> SignatureLevel.PADES_BASELINE_LT
				else -> SignatureLevel.PADES_BASELINE_B
			}
		}

	/**
	 * Compare only the persistable content fields of two states, ignoring
	 * transient UI properties like [saving], [error], and [certAddError].
	 */
	fun contentEquals(other: ProfileEditState): Boolean =
		profileName == other.profileName &&
				description == other.description &&
				hashAlgorithm == other.hashAlgorithm &&
				encryptionAlgorithm == other.encryptionAlgorithm &&
				signatureTimestampOverride == other.signatureTimestampOverride &&
				archivalTimestampOverride == other.archivalTimestampOverride &&
				timestampEnabled == other.timestampEnabled &&
				timestampUrl == other.timestampUrl &&
				timestampUsername == other.timestampUsername &&
				timestampPassword == other.timestampPassword &&
				hasStoredPassword == other.hasStoredPassword &&
				timestampTimeout == other.timestampTimeout &&
				disabledHashAlgorithms == other.disabledHashAlgorithms &&
				disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms &&
				trustedCertificates == other.trustedCertificates

	/**
	 * Convert this UI state back into a persistable [ProfileConfig].
	 *
	 * The [timestampPassword] is intentionally **not** included in the returned config
	 * because passwords are persisted separately through the OS credential store.
	 * The [TimestampServerConfig.credentialKey] is set to the username when a password
	 * has been entered or was already stored.
	 */
	fun toProfileConfig(): ProfileConfig = ProfileConfig(
		name = profileName,
		description = description.ifBlank { null },
		hashAlgorithm = hashAlgorithm,
		encryptionAlgorithm = encryptionAlgorithm,
		signatureLevel = effectiveSignatureLevel,
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
		disabledHashAlgorithms = disabledHashAlgorithms,
		disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
		validation = if (trustedCertificates.isNotEmpty()) {
			ValidationConfig(trustedCertificates = trustedCertificates)
		} else {
			null
		},
	)

	companion object {

		/**
		 * Build a [ProfileEditState] from an existing [ProfileConfig].
		 *
		 * @param profile The source profile configuration.
		 * @param hasStoredPassword Whether a password is already persisted in the credential store.
		 * @return A new edit state pre-populated with the profile's values.
		 */
		fun from(profile: ProfileConfig, hasStoredPassword: Boolean = false): ProfileEditState {
			val (sigTs, archTs) = toToggleStates(profile.signatureLevel)
			return ProfileEditState(
				profileName = profile.name,
				description = profile.description.orEmpty(),
				hashAlgorithm = profile.hashAlgorithm,
				encryptionAlgorithm = profile.encryptionAlgorithm,
				signatureTimestampOverride = sigTs,
				archivalTimestampOverride = archTs,
				timestampEnabled = profile.timestampServer != null,
				timestampUrl = profile.timestampServer?.url.orEmpty(),
				timestampUsername = profile.timestampServer?.username.orEmpty(),
				timestampPassword = "",
				hasStoredPassword = hasStoredPassword,
				timestampTimeout = (profile.timestampServer?.timeout ?: 30000).toString(),
				disabledHashAlgorithms = profile.disabledHashAlgorithms,
				disabledEncryptionAlgorithms = profile.disabledEncryptionAlgorithms,
				trustedCertificates = profile.validation?.trustedCertificates.orEmpty(),
			)
		}

		/**
		 * Map a nullable [SignatureLevel] profile override to the pair of
		 * [TriToggleState] values for the two timestamp toggles.
		 */
		private fun toToggleStates(
			level: SignatureLevel?,
		): Pair<TriToggleState, TriToggleState> = when (level) {
			null -> TriToggleState.INHERIT to TriToggleState.INHERIT
			SignatureLevel.PADES_BASELINE_B -> TriToggleState.DISABLED to TriToggleState.DISABLED
			SignatureLevel.PADES_BASELINE_T -> TriToggleState.ENABLED to TriToggleState.DISABLED
			SignatureLevel.PADES_BASELINE_LT -> TriToggleState.ENABLED to TriToggleState.DISABLED
			SignatureLevel.PADES_BASELINE_LTA -> TriToggleState.ENABLED to TriToggleState.ENABLED
		}
	}
}
