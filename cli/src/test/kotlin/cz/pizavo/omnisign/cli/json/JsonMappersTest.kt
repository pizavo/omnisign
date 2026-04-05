package cz.pizavo.omnisign.cli.json

import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Verifies all JSON mapper extension functions in [JsonMappers].
 */
class JsonMappersTest : FunSpec({

	val sampleCert = CertificateInfo(
		subjectDN = "CN=Test",
		issuerDN = "CN=CA",
		serialNumber = "ABCD",
		validFrom = Instant.parse("2025-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
		keyUsages = listOf("digitalSignature"),
		isQualified = true,
		publicKeyAlgorithm = "RSA",
		sha256Fingerprint = "AA:BB:CC",
	)

	test("OperationError.toJsonError maps all fields") {
		val error = SigningError.SigningFailed(
			message = "Failed",
			details = "Some detail",
			cause = RuntimeException("root cause"),
		)

		val json = error.toJsonError()
		json.message shouldBe "Failed"
		json.details shouldBe "Some detail"
		json.cause shouldBe "root cause"
	}

	test("OperationError.toJsonError handles null details and cause") {
		val error = ValidationError.InvalidDocument(message = "Bad file")

		val json = error.toJsonError()
		json.message shouldBe "Bad file"
		json.details.shouldBeNull()
		json.cause.shouldBeNull()
	}

	test("SigningResult.toJsonResult maps all fields") {
		val result = SigningResult(
			outputFile = "/out.pdf",
			signatureId = "sig-1",
			signatureLevel = "PAdES-BASELINE-B",
			annotatedWarnings = listOf(AnnotatedWarning("w1")),
			rawWarnings = listOf("raw1"),
		)

		val json = result.toJsonResult()
		json.success shouldBe true
		json.outputFile shouldBe "/out.pdf"
		json.signatureId shouldBe "sig-1"
		json.signatureLevel shouldBe "PAdES-BASELINE-B"
		json.warnings shouldBe listOf("w1")
		json.rawWarnings shouldBe listOf("raw1")
	}

	test("ArchivingResult.toJsonResult maps all fields") {
		val result = ArchivingResult(
			outputFile = "/ext.pdf",
			newSignatureLevel = "PAdES-BASELINE-LTA",
			annotatedWarnings = listOf(AnnotatedWarning("w2")),
			rawWarnings = listOf("raw2"),
		)

		val json = result.toJsonResult()
		json.success shouldBe true
		json.outputFile shouldBe "/ext.pdf"
		json.newLevel shouldBe "PAdES-BASELINE-LTA"
		json.warnings shouldBe listOf("w2")
	}

	test("ValidationReport.toJsonResult computes summary correctly") {
		val report = ValidationReport(
			documentName = "test.pdf",
			validationTime = Instant.parse("2026-01-01T00:00:00Z"),
			overallResult = ValidationResult.VALID,
			signatures = listOf(
				SignatureValidationResult(
					signatureId = "s1",
					indication = ValidationIndication.TOTAL_PASSED,
					signedBy = "A",
					signatureLevel = "PAdES-BASELINE-T",
					signatureTime = Instant.parse("2026-01-01T00:00:00Z"),
					certificate = sampleCert,
				),
				SignatureValidationResult(
					signatureId = "s2",
					indication = ValidationIndication.TOTAL_FAILED,
					signedBy = "B",
					signatureLevel = "PAdES-BASELINE-B",
					signatureTime = Instant.parse("2026-01-01T00:00:00Z"),
					certificate = sampleCert,
				),
			),
		)

		val json = report.toJsonResult()
		json.success shouldBe true
		val summary = json.summary.shouldNotBeNull()
		summary.total shouldBe 2
		summary.passed shouldBe 1
		summary.failed shouldBe 1
		summary.indeterminate shouldBe 0
	}

	test("ValidationReport.toJsonResult suppresses NOT_QUALIFIED overall trust tier") {
		val report = ValidationReport(
			documentName = "test.pdf",
			validationTime = Instant.parse("2026-01-01T00:00:00Z"),
			overallResult = ValidationResult.VALID,
			signatures = listOf(
				SignatureValidationResult(
					signatureId = "s1",
					indication = ValidationIndication.TOTAL_PASSED,
					signedBy = "A",
					signatureLevel = "PAdES-BASELINE-T",
					signatureTime = Instant.parse("2026-01-01T00:00:00Z"),
					certificate = sampleCert,
					trustTier = SignatureTrustTier.NOT_QUALIFIED,
				),
			),
		)

		val json = report.toJsonResult()
		json.overallTrustTier.shouldBeNull()
	}

	test("ValidationReport.toJsonResult includes qualified overall trust tier") {
		val report = ValidationReport(
			documentName = "test.pdf",
			validationTime = Instant.parse("2026-01-01T00:00:00Z"),
			overallResult = ValidationResult.VALID,
			signatures = listOf(
				SignatureValidationResult(
					signatureId = "s1",
					indication = ValidationIndication.TOTAL_PASSED,
					signedBy = "A",
					signatureLevel = "PAdES-BASELINE-T",
					signatureTime = Instant.parse("2026-01-01T00:00:00Z"),
					certificate = sampleCert,
					trustTier = SignatureTrustTier.QUALIFIED_QSCD,
				),
			),
		)

		val json = report.toJsonResult()
		json.overallTrustTier shouldBe "QUALIFIED_QSCD"
	}

	test("ValidationReport.toJsonResult passes rawReportPath") {
		val report = ValidationReport(
			documentName = "test.pdf",
			validationTime = Instant.parse("2026-01-01T00:00:00Z"),
			overallResult = ValidationResult.VALID,
			signatures = emptyList(),
		)

		val json = report.toJsonResult(rawReportPath = "/tmp/report.xml")
		json.rawReportPath shouldBe "/tmp/report.xml"
	}

	test("CertificateDiscoveryResult.toJsonCertificateList maps certificates") {
		val discovery = CertificateDiscoveryResult(
			certificates = listOf(
				AvailableCertificateInfo(
					alias = "cert1",
					subjectDN = "CN=User",
					issuerDN = "CN=CA",
					validFrom = Instant.parse("2024-01-01T00:00:00Z"),
					validTo = Instant.parse("2027-01-01T00:00:00Z"),
					tokenType = "PKCS12",
					keyUsages = listOf("digitalSignature"),
				)
			),
			tokenWarnings = listOf(
				TokenDiscoveryWarning("t1", "Token", "Access denied", "detail info"),
			),
		)

		val json = discovery.toJsonCertificateList()
		json.success shouldBe true
		json.certificates.shouldHaveSize(1)
		json.certificates.first().alias shouldBe "cert1"
		json.tokenWarnings.shouldHaveSize(1)
		json.tokenWarnings.first().tokenId shouldBe "t1"
		json.tokenWarnings.first().details shouldBe "detail info"
	}

	test("CertificateDiscoveryResult.toJsonCertificateList handles empty results") {
		val discovery = CertificateDiscoveryResult(certificates = emptyList())

		val json = discovery.toJsonCertificateList()
		json.success shouldBe true
		json.certificates.shouldBeEmpty()
		json.tokenWarnings.shouldBeEmpty()
	}
})

