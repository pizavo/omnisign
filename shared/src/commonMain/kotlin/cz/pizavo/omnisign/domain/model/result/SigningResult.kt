package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a signing operation.
 *
 * @property outputFile Absolute path of the signed output file.
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel Name of the PAdES level used (e.g. `PADES_BASELINE_B`).
 * @property warnings User-friendly, grouped warning summaries suitable for display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 * @property hasRevocationWarnings Whether any warnings relate to missing or failed revocation data.
 */
data class SigningResult(
	val outputFile: String,
	val signatureId: String,
	val signatureLevel: String,
	val warnings: List<String> = emptyList(),
	val rawWarnings: List<String> = emptyList(),
	val hasRevocationWarnings: Boolean = false,
)

