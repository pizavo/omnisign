package cz.pizavo.omnisign.domain.model.result

/**
 * Result of an archiving operation.
 *
 * @property outputFile Absolute path of the extended output file.
 * @property newSignatureLevel Name of the PAdES level the document was extended to.
 * @property warnings User-friendly, grouped warning summaries suitable for display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 */
data class ArchivingResult(
    val outputFile: String,
    val newSignatureLevel: String,
    val warnings: List<String> = emptyList(),
    val rawWarnings: List<String> = emptyList(),
)

