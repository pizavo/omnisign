package cz.pizavo.omnisign.domain.model.result

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * Result of an archiving operation.
 *
 * Carries the extended document as raw bytes; the caller at the platform boundary decides
 * where (or whether) to persist them — the CLI and desktop write to a chosen path, the server
 * streams them back in the HTTP response, and the web target hands them to the browser download.
 *
 * @property outputBytes Raw bytes of the extended PDF.
 * @property outputName Suggested document name for the extended output.
 * @property newSignatureLevel Display name of the PAdES level the output document actually reached,
 *   as read back from [outputBytes]. Because it has to name something, it falls back to the
 *   requested level when the produced document could not be inspected at all; [achievedLevel] is the
 *   field that distinguishes that case, and a caller deciding anything of consequence should read
 *   that one instead.
 * @property achievedLevel The level reached, typed — or `null` when it could not be determined,
 *   because the output could not be parsed or its signature falls outside the four PAdES baseline
 *   levels. `null` means *unknown*, not *low*: it is deliberately distinct from a level below the
 *   requested one, so a caller can refuse to act on an unconfirmed result rather than assume either
 *   way. Comparing it against the requested level is how a caller learns whether the operation
 *   delivered.
 * @property annotatedWarnings Warnings enriched with affected entity IDs for tooltip display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 * @property revocationDataMissing Whether the extension could not embed the revocation data the
 *   requested level needs, so [outputBytes] did not reach [newSignatureLevel]. The bytes are still
 *   returned — they carry whatever the extension did achieve — but a caller must not present them as
 *   the requested level: the desktop asks before saving them and the renewal scheduler refuses to
 *   overwrite the original with them.
 */
data class ArchivingResult(
    val outputBytes: ByteArray,
    val outputName: String,
    val newSignatureLevel: String,
    val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
    val rawWarnings: List<String> = emptyList(),
    val revocationDataMissing: Boolean = false,
    val achievedLevel: SignatureLevel? = null,
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
                rawWarnings == other.rawWarnings &&
                revocationDataMissing == other.revocationDataMissing &&
                achievedLevel == other.achievedLevel
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
        result = 31 * result + revocationDataMissing.hashCode()
        result = 31 * result + achievedLevel.hashCode()
        return result
    }
}
