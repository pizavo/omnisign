package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

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
	 * @property targetLevel The PAdES level to extend to.
	 * @property outputPath Suggested output file path.
	 */
	data class Ready(
		val targetLevel: SignatureLevel = SignatureLevel.PADES_BASELINE_T,
		val outputPath: String = "",
	) : TimestampDialogState

	/**
	 * An extension operation is in progress.
	 */
	data object Extending : TimestampDialogState

	/**
	 * Extension completed successfully.
	 *
	 * @property outputFile Path to the extended output file.
	 * @property newLevel Name of the new PAdES level.
	 * @property warnings Any warnings produced during extension.
	 */
	data class Success(
		val outputFile: String,
		val newLevel: String,
		val warnings: List<String> = emptyList(),
	) : TimestampDialogState

	/**
	 * Extension failed.
	 *
	 * @property message Error message.
	 * @property details Optional detailed error information.
	 */
	data class Error(
		val message: String,
		val details: String? = null,
	) : TimestampDialogState
}

