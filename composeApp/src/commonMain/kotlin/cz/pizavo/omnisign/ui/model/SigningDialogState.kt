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
	 * @property outputPath Suggested output file path.
	 * @property configHashAlgorithm Default hash algorithm from the resolved configuration.
	 * @property configAddSignatureTimestamp Whether the resolved config enables signature timestamps.
	 * @property configAddArchivalTimestamp Whether the resolved config enables archival timestamps.
	 * @property disabledHashAlgorithms Hash algorithms that are disabled in the current config.
	 * @property addToRenewalJob Whether the output file should be added to a renewal job after a successful LTA signing.
	 * @property coveringRenewalJobName Name of the existing renewal job that already covers [outputPath],
	 *   or `null` when no coverage exists. When non-null, the "Add to renewal job" checkbox is
	 *   forced checked and disabled because the file will be renewed regardless.
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
		val outputPath: String = "",
		val configHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
		val configAddSignatureTimestamp: Boolean = false,
		val configAddArchivalTimestamp: Boolean = false,
		val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
		val addToRenewalJob: Boolean = false,
		val coveringRenewalJobName: String? = null,
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
	 * @property message Error message.
	 * @property details Optional detailed error information.
	 */
	data class Error(
		val message: String,
		val details: String? = null,
	) : SigningDialogState
}
