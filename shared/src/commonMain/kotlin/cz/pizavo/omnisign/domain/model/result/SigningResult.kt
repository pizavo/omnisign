package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a signing operation.
 *
 * @property outputFile Absolute path of the signed output file.
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel Name of the PAdES level used (e.g. `PADES_BASELINE_B`).
 * @property annotatedWarnings Warnings enriched with affected entity IDs for tooltip display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 * @property hasRevocationWarnings Whether any warnings relate to missing or failed revocation data.
 */
data class SigningResult(
	val outputFile: String,
	val signatureId: String,
	val signatureLevel: String,
	val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
	val rawWarnings: List<String> = emptyList(),
	val hasRevocationWarnings: Boolean = false,
) {
	/**
	 * Plain-text warning summaries derived from [annotatedWarnings] for backward-compatible consumers.
	 */
	val warnings: List<String>
		get() = annotatedWarnings.map { it.summary }
}

