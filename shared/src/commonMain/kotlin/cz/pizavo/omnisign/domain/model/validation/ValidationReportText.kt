package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.displayLabel
import cz.pizavo.omnisign.domain.model.signature.roleLabel
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime

/**
 * Render this [ValidationReport] as a human-readable plain-text summary.
 *
 * The format mirrors what desktop users see when they export a validation report as `.txt`:
 * a header, per-signature blocks (indication, signer, level, certificate details, the certificate chain, revocation evidence, errors,
 * warnings, and any embedded signature/archive timestamps), a document-level timestamps block,
 * and finally any trusted-list warnings. The result is a pure
 * projection of the domain model — no DSS interaction, no I/O — so it is safe to call on any
 * Kotlin target and is the natural pairing of [cz.pizavo.omnisign.domain.model.validation.json.toJsonReport]
 * for callers that want a text equivalent.
 *
 * @param detailed When `true`, every certificate in a chain is followed by its full parsed dump —
 *   each [cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection] and field; when
 *   `false`, the chain is a one-line-per-certificate summary (role, common name, trust source).
 * @return Multi-line text representation of the report. Lines are separated with
 *   `appendLine`'s platform default line separator.
 */
fun ValidationReport.toPlainText(detailed: Boolean = false): String = buildString {
	fun StringBuilder.appendTimestampDetails(ts: TimestampValidationResult, pad: String) {
		appendLine("${pad}Indication:      ${ts.indication}")
		ts.subIndication?.let { appendLine("${pad}Sub-indication:  $it") }
		appendLine("${pad}Production time: ${ts.productionTime.formatDateTime()}")
		ts.qualification?.let { appendLine("${pad}Qualification:   $it") }
		ts.tsaSubjectDN?.let { appendLine("${pad}TSA:             $it") }
		if (ts.euLotlBacked) appendLine("${pad}EU LOTL:         Yes")
		appendCertificateChain(ts.chain, TrustedCertificateType.TSA, pad, detailed)
		if (ts.errors.isNotEmpty()) {
			appendLine("${pad}Errors:")
			ts.errors.forEach { appendLine("$pad  • $it") }
		}
		if (ts.warnings.isNotEmpty()) {
			appendLine("${pad}Warnings:")
			ts.warnings.forEach { appendLine("$pad  • $it") }
		}
		if (ts.infos.isNotEmpty()) {
			appendLine("${pad}Information:")
			ts.infos.forEach { appendLine("$pad  • $it") }
		}
	}

	fun StringBuilder.appendMessages(title: String, messages: List<String>) {
		if (messages.isEmpty()) return
		appendLine("  $title:")
		messages.forEach { appendLine("    • $it") }
	}

	appendLine("OmniSign — Validation Report")
	appendLine("════════════════════════════════════════")
	appendLine("Document:        $documentName")
	appendLine("Validation time: ${validationTime.formatDateTime()}")
	appendLine("Overall result:  $overallResult")
	if (overallTrustTier != SignatureTrustTier.NOT_QUALIFIED) {
		appendLine("Trust tier:      ${overallTrustTier.label().english()}")
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
				appendLine("  Trust tier:     ${sig.trustTier.label().english()}")
			}
			if (sig.euLotlBacked) {
				appendLine("  EU LOTL:        Yes")
			}
			sig.hashAlgorithm?.let { appendLine("  Hash algorithm: $it") }
			sig.encryptionAlgorithm?.let { appendLine("  Encryption:     $it") }
			appendMessages("Errors", sig.errors)
			appendMessages("Warnings", sig.warnings)
			appendMessages("Qualification Errors", sig.qualificationErrors)
			appendMessages("Qualification Warnings", sig.qualificationWarnings)
			appendMessages("Information", sig.infos)
			appendMessages("Qualification Information", sig.qualificationInfos + (sig.trustTier.qscdResidenceInfo()?.let { listOf(it.english()) } ?: emptyList()))
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
			appendCertificateChain(sig.certificate.chain, TrustedCertificateType.CA, "  ", detailed)
			if (sig.revocations.isNotEmpty()) {
				appendLine()
				appendLine("  Revocation:")
				sig.revocations.revocationConclusion(sig.signatureTime)?.let { appendLine("    ${it.english()}") }
				val labelWidth = sig.revocations.flatMap { it.displayRows() }.maxOf { it.first.english().length } + 1
				sig.revocations.forEach { revocation ->
					appendLine()
					revocation.displayRows().forEach { (label, value) ->
						appendLine("    ${"${label.english()}:".padEnd(labelWidth)} ${value.english()}")
					}
				}
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

/**
 * Append [chain] to this builder, rendered top-down for reading — the trust anchor first, the
 * end-entity last (the reverse of the leaf-first storage order) — one line per certificate carrying
 * its [roleLabel], common name, and any trust sources. When [detailed], each certificate is followed
 * by its full parsed dump (every [cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection]
 * and field, each value's continuation lines indented). Renders nothing when [chain] is empty.
 *
 * @param leafRole What the chain anchors, used to label the leaf — [TrustedCertificateType.TSA] for a
 *   timestamp's chain, otherwise a signature's.
 * @param pad Indentation for the "Certificate chain:" header; entries and details nest beneath it.
 * @param detailed Whether to append each certificate's full parsed dump under its summary line.
 */
private fun StringBuilder.appendCertificateChain(
	chain: List<CertificateChainLink>,
	leafRole: TrustedCertificateType,
	pad: String,
	detailed: Boolean,
) {
	if (chain.isEmpty()) return
	val entryPad = "$pad  "
	val sectionPad = "$entryPad  "
	val fieldPad = "$sectionPad  "
	appendLine("${pad}Certificate chain:")
	for (index in chain.indices.reversed()) {
		val link = chain[index]
		val role = link.roleLabel(isLeaf = index == 0, isTop = index == chain.lastIndex, leafRole = leafRole).english()
		val trust = if (link.trustedVia.isEmpty()) {
			""
		} else {
			" [trusted via ${link.trustedVia.joinToString(", ") { it.displayLabel().english() }}]"
		}
		appendLine("$entryPad${role}: ${link.commonName ?: link.subjectDN}$trust")
		if (detailed) {
			link.details.forEach { section ->
				appendLine("$sectionPad${section.title}:")
				section.fields.forEach { field ->
					val valueLines = field.value.split("\n")
					appendLine("$fieldPad${field.label}: ${valueLines.first()}")
					valueLines.drop(1).forEach { appendLine("$fieldPad  $it") }
				}
			}
		}
	}
}
