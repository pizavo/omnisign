package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlin.time.Instant

/**
 * Verifies that [toPlainText] renders both per-signature and document-level timestamps.
 */
class ValidationReportTextTest : FunSpec({

	val cert = CertificateInfo(
		subjectDN = "CN=Signer",
		issuerDN = "CN=CA",
		serialNumber = "1",
		validFrom = Instant.parse("2025-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
	)

	fun timestamp(type: String, tsa: String) = TimestampValidationResult(
		timestampId = "ts-$type",
		type = type,
		indication = ValidationIndication.TOTAL_PASSED,
		productionTime = Instant.parse("2026-02-02T12:00:00Z"),
		qualification = "QTSA",
		tsaSubjectDN = tsa,
	)

	val report = ValidationReport(
		documentName = "doc.pdf",
		validationTime = Instant.parse("2026-03-01T00:00:00Z"),
		overallResult = ValidationResult.VALID,
		signatures = listOf(
			SignatureValidationResult(
				signatureId = "sig-1",
				indication = ValidationIndication.TOTAL_PASSED,
				signedBy = "Alice",
				signatureLevel = "PAdES-BASELINE-LTA",
				signatureTime = Instant.parse("2026-02-01T09:00:00Z"),
				certificate = cert,
				timestamps = listOf(timestamp("Signature timestamp", "CN=Sig TSA")),
			),
		),
		timestamps = listOf(timestamp("Archive timestamp", "CN=Archive TSA")),
	)

	test("renders per-signature timestamps inside the signature block") {
		val text = report.toPlainText()
		text shouldContain "Timestamps (1):"
		text shouldContain "1. Signature timestamp"
		text shouldContain "CN=Sig TSA"
	}

	test("renders the document-level timestamps block") {
		val text = report.toPlainText()
		text shouldContain "── Document Timestamps ──"
		text shouldContain "Archive timestamp"
		text shouldContain "CN=Archive TSA"
	}

	test("includes timestamp production time and qualification for each entry") {
		val text = report.toPlainText()
		text shouldContain "Production time:"
		text shouldContain "QTSA"
	}
})
