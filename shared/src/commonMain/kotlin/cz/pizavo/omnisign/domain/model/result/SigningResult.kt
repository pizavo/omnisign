package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a signing operation.
 *
 * @property outputFile Absolute path of the signed output file.
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel Name of the PAdES level used (e.g. `PADES_BASELINE_B`).
 * @property warnings Non-fatal warnings produced during the signing operation, such as
 *   the use of an algorithm past its ETSI expiration date.
 */
data class SigningResult(
	val outputFile: String,
	val signatureId: String,
	val signatureLevel: String,
	val warnings: List<String> = emptyList()
)

