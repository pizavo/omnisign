package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
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
	 * @property selectedAlias Currently selected certificate alias, or `null` for auto.
	 * @property hashAlgorithm Hash algorithm override, or `null` to use the resolved config default.
	 * @property signatureLevel Signature level override, or `null` to use the resolved config default.
	 * @property reason Reason for signing.
	 * @property location Location of signing.
	 * @property contactInfo Contact information of the signer.
	 * @property addTimestamp Whether to include an RFC 3161 timestamp.
	 * @property outputPath Suggested output file path.
	 * @property configHashAlgorithm Default hash algorithm from the resolved configuration.
	 * @property configSignatureLevel Default signature level from the resolved configuration.
	 * @property disabledHashAlgorithms Hash algorithms that are disabled in the current config.
	 */
	data class Ready(
		val certificates: List<AvailableCertificateInfo> = emptyList(),
		val tokenWarnings: List<TokenDiscoveryWarning> = emptyList(),
		val selectedAlias: String? = null,
		val hashAlgorithm: HashAlgorithm? = null,
		val signatureLevel: SignatureLevel? = null,
		val reason: String = "",
		val location: String = "",
		val contactInfo: String = "",
		val addTimestamp: Boolean = true,
		val outputPath: String = "",
		val configHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
		val configSignatureLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
		val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	) : SigningDialogState

	/**
	 * A signing operation is in progress.
	 */
	data object Signing : SigningDialogState

	/**
	 * Signing completed successfully.
	 *
	 * @property outputFile Path to the signed output file.
	 * @property signatureId Identifier of the created signature.
	 * @property signatureLevel PAdES level of the created signature.
	 * @property warnings Any warnings produced during signing.
	 */
	data class Success(
		val outputFile: String,
		val signatureId: String,
		val signatureLevel: String,
		val warnings: List<String> = emptyList(),
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

