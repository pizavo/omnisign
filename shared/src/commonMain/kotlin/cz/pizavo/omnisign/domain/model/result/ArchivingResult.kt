package cz.pizavo.omnisign.domain.model.result

/**
 * Result of an archiving operation.
 *
 * Carries the extended document as raw bytes; the caller at the platform boundary decides
 * where (or whether) to persist them — the CLI and desktop write to a chosen path, the server
 * streams them back in the HTTP response, and the web target hands them to the browser download.
 *
 * @property outputBytes Raw bytes of the extended PDF.
 * @property outputName Suggested document name for the extended output.
 * @property newSignatureLevel Name of the PAdES level the document was extended to.
 * @property annotatedWarnings Warnings enriched with affected entity IDs for tooltip display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 */
data class ArchivingResult(
    val outputBytes: ByteArray,
    val outputName: String,
    val newSignatureLevel: String,
    val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
    val rawWarnings: List<String> = emptyList(),
) {
    /**
     * Plain-text English warning summaries derived from [annotatedWarnings] for backward-compatible
     * consumers (CLI, JSON). Frontends that localize render [annotatedWarnings] directly instead.
     */
    val warnings: List<String>
        get() = annotatedWarnings.map { it.summary.english() }

    /**
     * Structural equality that compares [outputBytes] by content rather than by reference.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchivingResult) return false
        return outputBytes.contentEquals(other.outputBytes) &&
                outputName == other.outputName &&
                newSignatureLevel == other.newSignatureLevel &&
                annotatedWarnings == other.annotatedWarnings &&
                rawWarnings == other.rawWarnings
    }

    /**
     * Hash code consistent with [equals], hashing [outputBytes] by content.
     */
    override fun hashCode(): Int {
        var result = outputBytes.contentHashCode()
        result = 31 * result + outputName.hashCode()
        result = 31 * result + newSignatureLevel.hashCode()
        result = 31 * result + annotatedWarnings.hashCode()
        result = 31 * result + rawWarnings.hashCode()
        return result
    }
}
