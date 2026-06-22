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
	 */
	data class Ready(
		val timestampType: TimestampType = TimestampType.ARCHIVAL_TIMESTAMP,
		val currentLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_B,
		val unavailableTypes: Set<TimestampType> = emptySet(),
		val suggestedName: String = "",
		val inputDirectory: String? = null,
		val addToRenewalJob: Boolean = false,
	) : TimestampDialogState

	/**
	 * An extension operation is in progress.
	 */
	data object Extending : TimestampDialogState

	/**
	 * Extension to B-LT failed because revocation data could not be obtained.
	 *
	 * The user can either accept a fallback to B-T (signature timestamp without
	 * revocation data) or abort. This state is only reachable when the document
	 * does not already contain LT-level data — if it does, an [Error] is shown
	 * instead.
	 *
	 * @property warnings Revocation-related warnings / error details, each resolved to display text by the UI.
	 * @property details Optional detailed error information from DSS.
	 */
	data class RevocationWarning(
		val warnings: List<LocalizableText>,
		val details: String? = null,
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
