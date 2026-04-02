package cz.pizavo.omnisign.ui.model

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
	 * @property disabledTypes Timestamp types that are not selectable (e.g. when the
	 *   document already has a document timestamp, [TimestampType.SIGNATURE_TIMESTAMP]
	 *   is disabled because DSS would reject the level degradation).
	 * @property outputPath Suggested output file path.
	 * @property addToRenewalJob Whether the output file should be added to a renewal job after a successful LTA extension.
	 * @property coveringRenewalJobName Name of the existing renewal job that already covers [outputPath],
	 *   or `null` when no coverage exists. When non-null the "Add to renewal job" checkbox is
	 *   forced checked and disabled because the file will be renewed regardless.
	 */
	data class Ready(
		val timestampType: TimestampType = TimestampType.ARCHIVAL_TIMESTAMP,
		val disabledTypes: Set<TimestampType> = emptySet(),
		val outputPath: String = "",
		val addToRenewalJob: Boolean = false,
		val coveringRenewalJobName: String? = null,
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
	 * @property warnings Revocation-related warnings / error details.
	 * @property details Optional detailed error information from DSS.
	 */
	data class RevocationWarning(
		val warnings: List<String>,
		val details: String? = null,
	) : TimestampDialogState

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
