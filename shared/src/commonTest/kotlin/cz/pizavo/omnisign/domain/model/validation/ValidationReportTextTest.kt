package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection
import cz.pizavo.omnisign.domain.model.signature.CertificateField
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.signature.CertificateTrustSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

	test("renders EU LOTL for a LOTL-backed timestamp") {
		val backed = report.copy(
			timestamps = listOf(timestamp("Archive timestamp", "CN=Archive TSA").copy(euLotlBacked = true)),
		)
		backed.toPlainText() shouldContain "EU LOTL:"
	}

	test("renders the revocation conclusion and method-aware fields per token") {
		val withRevocation = report.copy(
			signatures = listOf(
				report.signatures.first().copy(
					revocations = listOf(
						RevocationInfo(
							method = "OCSP",
							status = "GOOD",
							revoked = false,
							embedded = true,
							sealedByTimestamp = true,
							origin = "DSS_DICTIONARY",
							producedAt = Instant.parse("2026-02-01T09:00:00Z"),
						),
					),
				),
			),
		)

		val text = withRevocation.toPlainText()

		text shouldContain "Revocation:"
		text shouldContain "The signing certificate was not revoked as of"
		text shouldContain "Embedded in document, sealed by document timestamp"
		text shouldContain "Response produced:"
	}

	test("renders qualification and informational messages") {
		val withMessages = report.copy(
			signatures = listOf(
				report.signatures.first().copy(
					qualificationErrors = listOf("qual-err"),
					qualificationWarnings = listOf("qual-warn"),
					infos = listOf("an-info"),
					qualificationInfos = listOf("qual-info"),
				),
			),
		)

		val text = withMessages.toPlainText()

		text shouldContain "Qualification Errors:"
		text shouldContain "qual-err"
		text shouldContain "Qualification Warnings:"
		text shouldContain "Information:"
		text shouldContain "an-info"
		text shouldContain "Qualification Information:"
		text shouldContain "qual-info"
	}

	test("renders the certificate chain with roles and trust sources, expanding detail only when detailed") {
		val chained = report.copy(
			signatures = listOf(
				report.signatures.first().copy(
					certificate = cert.copy(
						chain = listOf(
							CertificateChainLink(
								commonName = "Leaf Signer",
								subjectDN = "CN=Leaf Signer",
								selfSigned = false,
								trustedVia = emptyList(),
								details = listOf(
									CertificateDetailSection("Extensions", listOf(CertificateField("Custom Extension", "critical-value"))),
								),
								der = byteArrayOf(1),
							),
							CertificateChainLink(
								commonName = "Trust Root",
								subjectDN = "CN=Trust Root",
								selfSigned = true,
								trustedVia = listOf(CertificateTrustSource.TrustedList("EU LOTL")),
								details = listOf(
									CertificateDetailSection("Subject", listOf(CertificateField("CN", "Trust Root"))),
								),
								der = byteArrayOf(2),
							),
						),
					),
				),
			),
		)

		val summary = chained.toPlainText()
		summary shouldContain "Certificate chain:"
		summary shouldContain "Signing certificate: Leaf Signer"
		summary shouldContain "Root CA: Trust Root [trusted via EU LOTL]"
		summary shouldNotContain "Custom Extension"

		val detailed = chained.toPlainText(detailed = true)
		detailed shouldContain "Custom Extension"
		detailed shouldContain "critical-value"
	}
})
