package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.lumo.components.TriToggleState

/**
 * Mutable-friendly UI state for the profile edit form.
 *
 * All fields mirror [ProfileConfig] but use primitive/nullable types suitable
 * for two-way data binding with Compose text fields and selectors.
 *
 * @property profileName The immutable profile name (displayed as a header, not editable).
 * @property loadedProfile The stored profile this form was loaded from, or `null` for a form built
 *   without one. Saving copies the form's fields onto it, so the settings the form does not show,
 *   such as the OCSP and CRL overrides, the validation policy and the algorithm expiry settings,
 *   are kept.
 * @property description Free-text description of the profile.
 * @property hashAlgorithm Selected hash algorithm override, or `null` to inherit from global.
 * @property encryptionAlgorithm Selected encryption algorithm override, or `null` to inherit.
 * @property signatureTimestampOverride Tri-state toggle for the signature timestamp (B-LT) override.
 * @property archivalTimestampOverride Tri-state toggle for the archival timestamp (B-LTA) override.
 * @property alertIfNotEuLotlOverride Tri-state override for the "alert if not on EU LOTL" validation setting.
 * @property allowExpiredCertificateOverride Tri-state override for allowing signing with an expired certificate.
 * @property timestampEnabled Whether the timestamp server section is enabled.
 * @property timestampUrl TSA endpoint URL.
 * @property timestampUsername HTTP Basic auth username for the TSA.
 * @property timestampPassword HTTP Basic auth password for the current edit session.
 * @property hasStoredPassword Whether a password is already persisted in the OS credential store.
 * @property timestampTimeout HTTP request timeout in milliseconds, stored as a string for the text field.
 * @property disabledHashAlgorithms Hash algorithms disabled by this profile.
 * @property disabledEncryptionAlgorithms Encryption algorithms disabled by this profile.
 * @property customTrustedLists Custom trusted list sources scoped to this profile.
 * @property trustedCertificates Certificates currently in this profile's app-managed trust scope
 *   (the baseline loaded when editing started). Display subtracts [pendingTrustedCertRemovals]
 *   and adds [pendingTrustedCertAdds] on top of this.
 * @property pendingTrustedCertAdds Certificates staged to be added to this profile's scope on save.
 * @property pendingTrustedCertRemovals Fingerprints of baseline certificates staged for removal on save.
 * @property trustedCertsAvailable Whether a trust store is bound. On the web it is the server's
 *   read-only store, so the section lists the server's certificates for this profile; it is
 *   `false` only when no trust store is bound at all.
 * @property trustedCertAddError Error from the last failed certificate add attempt, or `null`; the UI resolves it via `resolve()`.
 * @property saving Whether a save operation is currently in progress.
 * @property error Error from the last failed save, or `null`; the UI resolves it via `resolve()`.
 * @property tlAddError Human-readable error from the last failed trusted list add attempt, or `null`.
 */
data class ProfileEditState(
	val profileName: String,
	val loadedProfile: ProfileConfig? = null,
	val description: String = "",
	val hashAlgorithm: HashAlgorithm? = null,
	val encryptionAlgorithm: EncryptionAlgorithm? = null,
	val signatureTimestampOverride: TriToggleState = TriToggleState.INHERIT,
	val archivalTimestampOverride: TriToggleState = TriToggleState.INHERIT,
	val alertIfNotEuLotlOverride: TriToggleState = TriToggleState.INHERIT,
	val allowExpiredCertificateOverride: TriToggleState = TriToggleState.INHERIT,
	val timestampEnabled: Boolean = false,
	val timestampUrl: String = "",
	val timestampUsername: String = "",
	val timestampPassword: Sensitive<String> = Sensitive(""),
	val hasStoredPassword: Boolean = false,
	val timestampTimeout: String = TimestampServerConfig.DEFAULT_TIMEOUT_MS.toString(),
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	val customTrustedLists: List<CustomTrustedListConfig> = emptyList(),
	val trustedCertificates: List<TrustedCertificate> = emptyList(),
	val pendingTrustedCertAdds: List<PendingTrustedCert> = emptyList(),
	val pendingTrustedCertRemovals: Set<String> = emptySet(),
	val trustedCertsAvailable: Boolean = true,
	val trustedCertAddError: TrustedCertAddError? = null,
	val saving: Boolean = false,
	val error: ProfileError? = null,
	val tlAddError: String? = null,
) {

	/**
	 * The PAdES [SignatureLevel] override the two toggle states save, or `null` when both are
	 * [TriToggleState.INHERIT] and the profile inherits the global level.
	 *
	 * A toggle left on [TriToggleState.INHERIT] while the other one is set takes its part of
	 * [globalLevel]: the archival timestamp is on when the global level is B-LTA, and the signature
	 * timestamp follows the global B-T or B-LT (B-LT when the global level is B-LTA). The level is
	 * worked out when the profile is saved, so it does not follow later changes to the global level.
	 *
	 * While both toggles still show the level of [loadedProfile], that level is kept as it is. The
	 * toggles cannot tell B-T from B-LT, so a B-T override set with the CLI survives a save that
	 * leaves them alone.
	 *
	 * @param globalLevel The global default signature level.
	 */
	fun effectiveSignatureLevel(globalLevel: SignatureLevel): SignatureLevel? {
		val loaded = loadedProfile
		if (loaded != null &&
			toToggleStates(loaded.signatureLevel) == (signatureTimestampOverride to archivalTimestampOverride)
		) return loaded.signatureLevel

		if (signatureTimestampOverride == TriToggleState.INHERIT &&
			archivalTimestampOverride == TriToggleState.INHERIT
		) return null

		val archival = when (archivalTimestampOverride) {
			TriToggleState.ENABLED -> true
			TriToggleState.DISABLED -> false
			TriToggleState.INHERIT -> globalLevel == SignatureLevel.PADES_BASELINE_LTA
		}

		return when {
			archival -> SignatureLevel.PADES_BASELINE_LTA
			signatureTimestampOverride == TriToggleState.ENABLED -> SignatureLevel.PADES_BASELINE_LT
			signatureTimestampOverride == TriToggleState.DISABLED -> SignatureLevel.PADES_BASELINE_B
			else -> when (globalLevel) {
				SignatureLevel.PADES_BASELINE_B -> SignatureLevel.PADES_BASELINE_B
				SignatureLevel.PADES_BASELINE_T -> SignatureLevel.PADES_BASELINE_T
				SignatureLevel.PADES_BASELINE_LT, SignatureLevel.PADES_BASELINE_LTA -> SignatureLevel.PADES_BASELINE_LT
			}
		}
	}

	/**
	 * Compare only the persistable content fields of two states, ignoring
	 * transient UI properties like [saving], [error], and [tlAddError].
	 */
	fun contentEquals(other: ProfileEditState): Boolean =
		profileName == other.profileName &&
				description == other.description &&
				hashAlgorithm == other.hashAlgorithm &&
				encryptionAlgorithm == other.encryptionAlgorithm &&
				signatureTimestampOverride == other.signatureTimestampOverride &&
				archivalTimestampOverride == other.archivalTimestampOverride &&
				alertIfNotEuLotlOverride == other.alertIfNotEuLotlOverride &&
				allowExpiredCertificateOverride == other.allowExpiredCertificateOverride &&
				timestampEnabled == other.timestampEnabled &&
				timestampUrl == other.timestampUrl &&
				timestampUsername == other.timestampUsername &&
				timestampPassword == other.timestampPassword &&
				hasStoredPassword == other.hasStoredPassword &&
				timestampTimeout == other.timestampTimeout &&
				disabledHashAlgorithms == other.disabledHashAlgorithms &&
				disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms &&
				customTrustedLists == other.customTrustedLists &&
				pendingTrustedCertAdds == other.pendingTrustedCertAdds &&
				pendingTrustedCertRemovals == other.pendingTrustedCertRemovals

	/**
	 * Convert this UI state back into a persistable [ProfileConfig].
	 *
	 * The form's fields are copied onto [loadedProfile], so whatever the form does not show is
	 * saved as it was loaded: the OCSP and CRL overrides, and in the validation section everything
	 * but the trusted lists and the two validation toggles. A form without a [loadedProfile] starts
	 * from an empty profile. The validation section is left out when nothing is left in it.
	 *
	 * The [timestampPassword] is intentionally **not** included in the returned config
	 * because passwords are persisted separately through the OS credential store.
	 * The [TimestampServerConfig.credentialKey] is set to the username when a password
	 * has been entered or was already stored.
	 *
	 * @param globalLevel The global default signature level, which a timestamp toggle left on
	 *   [TriToggleState.INHERIT] takes its part of (see [effectiveSignatureLevel]).
	 */
	fun toProfileConfig(globalLevel: SignatureLevel): ProfileConfig =
		(loadedProfile ?: ProfileConfig(name = profileName)).copy(
			name = profileName,
			description = description.ifBlank { null },
			hashAlgorithm = hashAlgorithm,
			encryptionAlgorithm = encryptionAlgorithm,
			signatureLevel = effectiveSignatureLevel(globalLevel),
			timestampServer = if (timestampEnabled && timestampUrl.isNotBlank()) {
				val effectiveUsername = timestampUsername.ifBlank { null }
				val hasPassword = timestampPassword.value.isNotEmpty() || hasStoredPassword
				TimestampServerConfig(
					url = timestampUrl.trim(),
					username = effectiveUsername,
					credentialKey = if (hasPassword && effectiveUsername != null) effectiveUsername else null,
					timeout = timestampTimeout.toIntOrNull() ?: TimestampServerConfig.DEFAULT_TIMEOUT_MS,
				)
			} else {
				null
			},
			disabledHashAlgorithms = disabledHashAlgorithms,
			disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
			validation = run {
				val alertOverride = when (alertIfNotEuLotlOverride) {
					TriToggleState.ENABLED -> true
					TriToggleState.DISABLED -> false
					TriToggleState.INHERIT -> null
				}
				val allowExpiredOverride = when (allowExpiredCertificateOverride) {
					TriToggleState.ENABLED -> true
					TriToggleState.DISABLED -> false
					TriToggleState.INHERIT -> null
				}
				(loadedProfile?.validation ?: ValidationConfig()).copy(
					customTrustedLists = customTrustedLists,
					alertIfNotEuLotl = alertOverride,
					allowExpiredCertificate = allowExpiredOverride,
				).takeUnless { it == ValidationConfig() }
			},
		)

	companion object {

		/**
		 * Build a [ProfileEditState] from an existing [ProfileConfig].
		 *
		 * @param profile The source profile configuration, kept as [loadedProfile].
		 * @param hasStoredPassword Whether a password is already persisted in the credential store.
		 * @param trustedCertificates Certificates currently in this profile's app-managed trust scope.
		 * @return A new edit state pre-populated with the profile's values.
		 */
		fun from(
			profile: ProfileConfig,
			hasStoredPassword: Boolean = false,
			trustedCertificates: List<TrustedCertificate> = emptyList(),
		): ProfileEditState {
			val (sigTs, archTs) = toToggleStates(profile.signatureLevel)
			return ProfileEditState(
				profileName = profile.name,
				loadedProfile = profile,
				description = profile.description.orEmpty(),
				hashAlgorithm = profile.hashAlgorithm,
				encryptionAlgorithm = profile.encryptionAlgorithm,
				signatureTimestampOverride = sigTs,
				archivalTimestampOverride = archTs,
				alertIfNotEuLotlOverride = when (profile.validation?.alertIfNotEuLotl) {
					true -> TriToggleState.ENABLED
					false -> TriToggleState.DISABLED
					null -> TriToggleState.INHERIT
				},
				allowExpiredCertificateOverride = when (profile.validation?.allowExpiredCertificate) {
					true -> TriToggleState.ENABLED
					false -> TriToggleState.DISABLED
					null -> TriToggleState.INHERIT
				},
				timestampEnabled = profile.timestampServer != null,
				timestampUrl = profile.timestampServer?.url.orEmpty(),
				timestampUsername = profile.timestampServer?.username.orEmpty(),
				hasStoredPassword = hasStoredPassword,
				timestampTimeout = (profile.timestampServer?.timeout ?: TimestampServerConfig.DEFAULT_TIMEOUT_MS).toString(),
				disabledHashAlgorithms = profile.disabledHashAlgorithms,
				disabledEncryptionAlgorithms = profile.disabledEncryptionAlgorithms,
				customTrustedLists = profile.validation?.customTrustedLists.orEmpty(),
				trustedCertificates = trustedCertificates,
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
