package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime

/**
 * Render this [ValidationReport] as a human-readable plain-text summary.
 *
 * The format mirrors what desktop users see when they export a validation report as `.txt`:
 * a header, per-signature blocks (indication, signer, level, certificate details, errors,
 * warnings, and any embedded signature/archive timestamps), a document-level timestamps block,
 * and finally any trusted-list warnings. The result is a pure
 * projection of the domain model — no DSS interaction, no I/O — so it is safe to call on any
 * Kotlin target and is the natural pairing of [cz.pizavo.omnisign.domain.model.validation.json.toJsonReport]
 * for callers that want a text equivalent.
 *
 * @return Multi-line text representation of the report. Lines are separated with
 *   `appendLine`'s platform default line separator.
 */
fun ValidationReport.toPlainText(): String = buildString {
	fun StringBuilder.appendTimestampDetails(ts: TimestampValidationResult, pad: String) {
		appendLine("${pad}Indication:      ${ts.indication}")
		ts.subIndication?.let { appendLine("${pad}Sub-indication:  $it") }
		appendLine("${pad}Production time: ${ts.productionTime.formatDateTime()}")
		ts.qualification?.let { appendLine("${pad}Qualification:   $it") }
		ts.tsaSubjectDN?.let { appendLine("${pad}TSA:             $it") }
		if (ts.euLotlBacked) appendLine("${pad}EU LOTL:         Yes")
		if (ts.errors.isNotEmpty()) {
			appendLine("${pad}Errors:")
			ts.errors.forEach { appendLine("$pad  • $it") }
		}
		if (ts.warnings.isNotEmpty()) {
			appendLine("${pad}Warnings:")
			ts.warnings.forEach { appendLine("$pad  • $it") }
		}
	}

	appendLine("OmniSign — Validation Report")
	appendLine("════════════════════════════════════════")
	appendLine("Document:        $documentName")
	appendLine("Validation time: ${validationTime.formatDateTime()}")
	appendLine("Overall result:  $overallResult")
	if (overallTrustTier != SignatureTrustTier.NOT_QUALIFIED) {
		appendLine("Trust tier:      ${overallTrustTier.label}")
	}
	appendLine()

	if (signatures.isEmpty()) {
		appendLine("No signatures found in the document.")
	} else {
		signatures.forEachIndexed { index, sig ->
			appendLine("── Signature ${index + 1} of ${signatures.size} ──")
			appendLine("  Indication:     ${sig.indication}")
			sig.subIndication?.let { appendLine("  Sub-indication: $it") }
			appendLine("  Signed by:      ${sig.signedBy}")
			appendLine("  Level:          ${sig.signatureLevel}")
			appendLine("  Time:           ${sig.signatureTime.formatDateTime()}")
			sig.signatureQualification?.let { appendLine("  Qualification:  $it") }
			if (sig.trustTier != SignatureTrustTier.NOT_QUALIFIED) {
				appendLine("  Trust tier:     ${sig.trustTier.label}")
			}
			if (sig.euLotlBacked) {
				appendLine("  EU LOTL:        Yes")
			}
			sig.hashAlgorithm?.let { appendLine("  Hash algorithm: $it") }
			sig.encryptionAlgorithm?.let { appendLine("  Encryption:     $it") }
			appendLine()
			appendLine("  Certificate:")
			appendLine("    Subject:      ${sig.certificate.subjectDN}")
			appendLine("    Issuer:       ${sig.certificate.issuerDN}")
			appendLine("    Serial:       ${sig.certificate.serialNumber}")
			appendLine("    Valid from:   ${sig.certificate.validFrom.formatDate()}")
			appendLine("    Valid to:     ${sig.certificate.validTo.formatDate()}")
			if (sig.certificate.keyUsages.isNotEmpty()) {
				appendLine("    Key usages:   ${sig.certificate.keyUsages.joinToString()}")
			}
			sig.certificate.publicKeyAlgorithm?.let { appendLine("    Public key:   $it") }
			sig.certificate.sha256Fingerprint?.let { appendLine("    SHA-256:      $it") }
			if (sig.errors.isNotEmpty()) {
				appendLine("  Errors:")
				sig.errors.forEach { appendLine("    • $it") }
			}
			if (sig.warnings.isNotEmpty()) {
				appendLine("  Warnings:")
				sig.warnings.forEach { appendLine("    • $it") }
			}
			if (sig.timestamps.isNotEmpty()) {
				appendLine()
				appendLine("  Timestamps (${sig.timestamps.size}):")
				sig.timestamps.forEachIndexed { tsIndex, ts ->
					appendLine("    ${tsIndex + 1}. ${ts.type}")
					appendTimestampDetails(ts, "      ")
				}
			}
			appendLine()
		}
	}

	if (timestamps.isNotEmpty()) {
		appendLine("── Document Timestamps ──")
		timestamps.forEachIndexed { index, ts ->
			appendLine("  ${index + 1}. ${ts.type}")
			appendTimestampDetails(ts, "    ")
			appendLine()
		}
	}

	if (tlWarnings.isNotEmpty()) {
		appendLine("── Trusted List Warnings ──")
		tlWarnings.forEach { appendLine("  ⚠ $it") }
	}
}
