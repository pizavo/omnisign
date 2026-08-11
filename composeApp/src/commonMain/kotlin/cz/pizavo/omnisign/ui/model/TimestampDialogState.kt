package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.text.LocalizableText

/**
 * UI state for the timestamp / extension dialog.
 *
 * Modeled as a sealed interface so that the Compose layer can pattern-match
 * on the current phase and render the appropriate content.
 */
sealed interface TimestampDialogState {

	/**
	 * The dialog is closed or not yet opened.
	 */
	data object Idle : TimestampDialogState

	/**
	 * The extension form is ready for user input.
	 *
	 * @property timestampType The selected timestamp operation type.
	 * @property currentLevel The document's current PAdES level, used to label each option by the
	 *   change it performs rather than by its target level name.
	 * @property unavailableTypes Timestamp types omitted from the dropdown because they do not
	 *   apply at [currentLevel] (e.g. once the document has a document timestamp, extending back to
	 *   B-LT would be a level degradation DSS rejects, so [TimestampType.SIGNATURE_TIMESTAMP] is not
	 *   offered).
	 * @property suggestedName Default file-name stem (no extension) for the save dialog, e.g. `contract-extended`.
	 * @property inputDirectory Source-document directory used as the save dialog's initial location; `null` on the web target.
	 * @property addToRenewalJob Whether to offer adding the output file to a renewal job after a successful LTA extension.
	 * @property ltMaterialUsable Whether the long-term validation material the document already
	 *   carries can be used. When `false` the document is at [currentLevel] in form only — its
	 *   revocation data is there but no validator will accept it, and the signing certificate has
	 *   expired, so no replacement can be obtained. The dialog states that rather than proposing a
	 *   remedy that does not exist.
	 */
	data class Ready(
		val timestampType: TimestampType = TimestampType.ARCHIVAL_TIMESTAMP,
		val currentLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
		val unavailableTypes: Set<TimestampType> = emptySet(),
		val suggestedName: String = "",
		val inputDirectory: String? = null,
		val addToRenewalJob: Boolean = false,
		val ltMaterialUsable: Boolean = true,
	) : TimestampDialogState

	/**
	 * An extension operation is in progress.
	 */
	data object Extending : TimestampDialogState

	/**
	 * Extension produced the extended bytes (held in the ViewModel) and the UI must now prompt for a
	 * save location. Reached after a successful extension, or after the user continues past a
	 * [RevocationWarning] (which re-runs the extension at B-T).
	 *
	 * No file has been written yet: the save dialog is the last step, so cancelling it discards the
	 * extended bytes and returns to [Ready] with nothing on disk.
	 *
	 * @property suggestedName Default file-name stem for the save dialog (no extension).
	 * @property inputDirectory Source-document directory used to seed the save dialog; `null` on web.
	 */
	data class AwaitingSave(
		val suggestedName: String,
		val inputDirectory: String?,
	) : TimestampDialogState

	/**
	 * The extension could not obtain the revocation data the requested level needs, so the user has
	 * to decide how to proceed. Reached in two distinct ways, told apart by [outputHeld]:
	 *
	 * - **The extension failed outright** ([outputHeld] `false`) — DSS could not reach the CRL/OCSP
	 *   endpoints at all and threw, so no bytes exist. Continuing re-runs the extension at B-T.
	 *   Only reachable when the document does not already contain LT-level data; if it does, an
	 *   [Error] is shown instead.
	 * - **The extension produced a document below its target level** ([outputHeld] `true`) — DSS
	 *   obtained no usable revocation data but still wrote a DSS dictionary, so the bytes exist and
	 *   are held in the ViewModel. Continuing saves them as they are; nothing is re-run.
	 *
	 * @property warnings Revocation-related warnings / error details, each resolved to display text by the UI.
	 * @property details Optional detailed error information from DSS.
	 * @property outputHeld Whether extended bytes are already held, which decides both the
	 *   explanation shown and what continuing does.
	 */
	data class RevocationWarning(
		val warnings: List<LocalizableText>,
		val details: String? = null,
		val outputHeld: Boolean = false,
	) : TimestampDialogState

	/**
	 * Extension completed successfully.
	 *
	 * @property outputFile Path to the extended output file.
	 * @property newLevel Name of the new PAdES level.
	 * @property warnings Annotated warnings produced during extension.
	 */
	data class Success(
		val outputFile: String,
		val newLevel: String,
		val warnings: List<AnnotatedWarning> = emptyList(),
	) : TimestampDialogState

	/**
	 * Extension failed.
	 *
	 * @property content Locale-agnostic error data the UI resolves to display text.
	 */
	data class Error(val content: ErrorMessage) : TimestampDialogState
}
