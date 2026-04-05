package cz.pizavo.omnisign.domain.model.result

/**
 * Result of an archiving operation.
 *
 * @property outputFile Absolute path of the extended output file.
 * @property newSignatureLevel Name of the PAdES level the document was extended to.
 * @property annotatedWarnings Warnings enriched with affected entity IDs for tooltip display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 */
data class ArchivingResult(
    val outputFile: String,
    val newSignatureLevel: String,
    val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
    val rawWarnings: List<String> = emptyList(),
) {
    /**
     * Plain-text warning summaries derived from [annotatedWarnings] for backward-compatible consumers.
     */
    val warnings: List<String>
        get() = annotatedWarnings.map { it.summary }
}

