package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a signing operation.
 *
 * Carries the signed document as in-memory bytes so the same result shape works for the
 * JVM in-process signer and for the web target (whose `RemoteSigningRepository` reads the
 * bytes straight from the `application/pdf` response body). Callers that ultimately want
 * the file on disk write [outputBytes] themselves at the boundary (CLI Sign command,
 * desktop "Save signed PDF" picker).
 *
 * @property outputBytes Raw signed PDF bytes.
 * @property outputName File name to surface to the user when persisting the result (used by
 *   the CLI as the on-disk file name and by the desktop "Save As" dialog as the suggested
 *   name).
 * @property signatureId DSS-assigned identifier of the created signature.
 * @property signatureLevel Name of the PAdES level the signed document **reached** (e.g.
 *   `PADES_BASELINE_B`), read back out of the produced bytes rather than echoed from the request, so
 *   that a B-LT request whose revocation data could not be embedded is reported as the B-T it is.
 *   Falls back to the requested level only when the level could not be established at all — a
 *   document that cannot be re-parsed — where naming the request is more useful than naming nothing.
 * @property annotatedWarnings Warnings enriched with affected entity IDs for tooltip display.
 * @property rawWarnings Original, unsanitized warning strings from DSS for verbose / JSON output.
 * @property hasRevocationWarnings Whether any warnings relate to missing or failed revocation data.
 */
data class SigningResult(
	val outputBytes: ByteArray,
	val outputName: String,
	val signatureId: String,
	val signatureLevel: String,
	val annotatedWarnings: List<AnnotatedWarning> = emptyList(),
	val rawWarnings: List<String> = emptyList(),
	val hasRevocationWarnings: Boolean = false,
) {
	/**
	 * Plain-text English warning summaries derived from [annotatedWarnings] for backward-compatible
	 * consumers (CLI, JSON). Frontends that localize render [annotatedWarnings] directly instead.
	 */
	val warnings: List<String>
		get() = annotatedWarnings.map { it.summary.english() }

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is SigningResult) return false
		return outputName == other.outputName &&
			outputBytes.contentEquals(other.outputBytes) &&
			signatureId == other.signatureId &&
			signatureLevel == other.signatureLevel &&
			annotatedWarnings == other.annotatedWarnings &&
			rawWarnings == other.rawWarnings &&
			hasRevocationWarnings == other.hasRevocationWarnings
	}

	override fun hashCode(): Int {
		var result = outputBytes.contentHashCode()
		result = 31 * result + outputName.hashCode()
		result = 31 * result + signatureId.hashCode()
		result = 31 * result + signatureLevel.hashCode()
		result = 31 * result + annotatedWarnings.hashCode()
		result = 31 * result + rawWarnings.hashCode()
		result = 31 * result + hasRevocationWarnings.hashCode()
		return result
	}
}

