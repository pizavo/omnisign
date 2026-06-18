package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.LockedTokenInfo
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning

/**
 * UI state for the signing dialog.
 *
 * Modeled as a sealed interface so that the Compose layer can pattern-match
 * on the current phase and render the appropriate content.
 */
sealed interface SigningDialogState {

	/**
	 * The dialog is closed or not yet opened.
	 */
	data object Idle : SigningDialogState

	/**
	 * Certificates are being discovered from available token sources.
	 */
	data object Loading : SigningDialogState

	/**
	 * Signing is blocked before the form opens because the resolved configuration mandates a
	 * signature level that embeds an RFC 3161 timestamp (any level above
	 * [SignatureLevel.PADES_BASELINE_B]), but the server holding the signing identity has its
	 * timestamping operation disabled.
	 *
	 * Reached from [cz.pizavo.omnisign.ui.viewmodel.SigningViewModel.open] when its
	 * `allowTimestamping` flag is `false` (the server omits `TIMESTAMP` from its capabilities)
	 * and the resolved [requiredLevel] is not [SignatureLevel.PADES_BASELINE_B]. Signing here
	 * would silently downgrade to B-B, dropping the timestamp the profile requires — so the
	 * dialog refuses rather than emit a weaker signature under the profile's name. The user must
	 * select a profile whose level the server can satisfy, or ask the administrator to enable
	 * timestamping.
	 *
	 * Mirrors the server-side `TIMESTAMP_NOT_ALLOWED` guard so the same policy holds whether the
	 * client pre-checks it or a request reaches the server directly.
	 *
	 * @property profileName Name of the active profile that mandates [requiredLevel], or `null`
	 *   when the level comes from the global default rather than a named profile.
	 * @property requiredLevel The resolved signature level that requires a timestamp.
	 */
	data class TimestampingUnavailable(
		val profileName: String?,
		val requiredLevel: SignatureLevel,
	) : SigningDialogState

	/**
	 * Certificates have been loaded and the signing form is ready for user input.
	 *
	 * @property certificates Available signing certificates.
	 * @property tokenWarnings Per-token warnings encountered during discovery.
	 * @property lockedTokens Tokens that require a PIN to list certificates.
	 * @property selectedAlias Currently selected certificate alias, or `null` when no certificate has been selected yet.
	 * @property hashAlgorithm Hash algorithm override, or `null` to use the resolved config default.
	 * @property addSignatureTimestamp Whether to include a signature timestamp and revocation data (B-LT).
	 * @property addArchivalTimestamp Whether to include an archival document timestamp (B-LTA).
	 * @property reason Reason for signing.
	 * @property location Location of signing.
	 * @property contactInfo Contact information of the signer.
	 * @property suggestedName Default file-name stem (no extension) for the save dialog, e.g. `contract-signed`.
	 * @property inputDirectory Source-document directory used as the save dialog's initial location; `null` on the web target.
	 * @property configHashAlgorithm Default hash algorithm from the resolved configuration.
	 * @property configAddSignatureTimestamp Whether the resolved config enables signature timestamps.
	 * @property configAddArchivalTimestamp Whether the resolved config enables archival timestamps.
	 * @property disabledHashAlgorithms Hash algorithms that are disabled in the current config.
	 * @property addToRenewalJob Whether to offer adding the output file to a renewal job after a successful LTA signing.
	 * @property refreshing `true` while a background PKCS#11 discovery cycle is in flight after the
	 *   dialog has already opened — typically triggered by a PC/SC reader-state event (card inserted /
	 *   removed, reader plugged / unplugged).  The UI binds a small inline indicator to this flag and
	 *   re-fetches the certificate list once it returns to `false`, preserving the current
	 *   [selectedAlias] when it is still present in the refreshed list.
	 */
	data class Ready(
		val certificates: List<AvailableCertificateInfo> = emptyList(),
		val tokenWarnings: List<TokenDiscoveryWarning> = emptyList(),
		val lockedTokens: List<LockedTokenInfo> = emptyList(),
		val selectedAlias: String? = null,
		val hashAlgorithm: HashAlgorithm? = null,
		val addSignatureTimestamp: Boolean = true,
		val addArchivalTimestamp: Boolean = false,
		val reason: String = "",
		val location: String = "",
		val contactInfo: String = "",
		val suggestedName: String = "",
		val inputDirectory: String? = null,
		val configHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
		val configAddSignatureTimestamp: Boolean = false,
		val configAddArchivalTimestamp: Boolean = false,
		val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
		val addToRenewalJob: Boolean = false,
		val refreshing: Boolean = false,
	) : SigningDialogState {

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
		 * Whether the signing operation should include an RFC 3161 timestamp.
		 *
		 * True when any timestamp checkbox is checked.
		 */
		val effectiveAddTimestamp: Boolean
			get() = addSignatureTimestamp || addArchivalTimestamp
	}

	/**
	 * A signing operation is in progress.
	 */
	data object Signing : SigningDialogState

	/**
	 * Signing completed but revocation data could not be obtained.
	 *
	 * Shown when the effective level is ≥ B-LT and the signing result
	 * contains revocation-related warnings. The user can abort (discard the
	 * output) or continue to the success screen.
	 *
	 * @property warnings Annotated warning summaries with affected certificate IDs.
	 * @property outputFile Path to the signed output file.
	 * @property signatureId Identifier of the created signature.
	 * @property signatureLevel PAdES level of the created signature.
	 */
	data class RevocationWarning(
		val warnings: List<AnnotatedWarning>,
		val outputFile: String,
		val signatureId: String,
		val signatureLevel: String,
	) : SigningDialogState

	/**
	 * Signing completed successfully.
	 *
	 * @property outputFile Path to the signed output file.
	 * @property signatureId Identifier of the created signature.
	 * @property signatureLevel PAdES level of the created signature.
	 * @property warnings Annotated warnings produced during signing.
	 */
	data class Success(
		val outputFile: String,
		val signatureId: String,
		val signatureLevel: String,
		val warnings: List<AnnotatedWarning> = emptyList(),
	) : SigningDialogState

	/**
	 * Signing or certificate loading failed.
	 *
	 * @property content Locale-agnostic error data the UI resolves to display text.
	 */
	data class Error(val content: ErrorMessage) : SigningDialogState
}
