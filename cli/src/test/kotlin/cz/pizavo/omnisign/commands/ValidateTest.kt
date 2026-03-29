package cz.pizavo.omnisign.commands

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module
import java.io.File
import kotlin.time.Instant

/**
 * Behavioral tests for the [Validate] command verifying stdout/stderr output,
 * exit codes, and JSON mode.
 */
class ValidateTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val validationRepository: ValidationRepository = mockk()
	val configRepository: ConfigRepository = mockk()
	
	val defaultConfig = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
		)
	)
	
	val sampleReport = ValidationReport(
		documentName = "test.pdf",
		validationTime = Instant.parse("2026-03-14T10:00:00Z"),
		overallResult = ValidationResult.VALID,
		signatures = listOf(
			SignatureValidationResult(
				signatureId = "sig-1",
				indication = ValidationIndication.TOTAL_PASSED,
				signedBy = "Test Signer",
				signatureLevel = "PAdES-BASELINE-T",
				signatureTime = Instant.parse("2026-03-14T09:00:00Z"),
				certificate = CertificateInfo(
					subjectDN = "CN=Test",
					issuerDN = "CN=CA",
					serialNumber = "1234",
					validFrom = Instant.parse("2025-01-01T00:00:00Z"),
					validTo = Instant.parse("2027-01-01T00:00:00Z"),
				),
			)
		),
	)
	
	fun tmpFile(name: String) = File(tmpDir, name).also { it.createNewFile() }
	
	extension(
		KoinExtension(
			module {
				single { ValidateDocumentUseCase(validationRepository) }
				single { configRepository }
				single<PasswordCallback> { mockk() }
			},
			mode = KoinLifecycleMode.Test
		)
	)
	
	beforeTest {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig
	}
	

	test("validate command should be instantiable") {
		Validate().shouldNotBeNull()
	}
	
	test("successful validation prints report to stdout") {
		val input = tmpFile("signed.pdf")
		coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		result.output shouldContain "VALIDATION REPORT"
		result.output shouldContain "test.pdf"
		result.output shouldContain "VALID"
		result.output shouldContain "Test Signer"
		result.statusCode shouldBe 0
	}
	
	test("validation error exits with code 1") {
		val input = tmpFile("bad.pdf")
		coEvery { validationRepository.validateDocument(any()) } returns ValidationError.ValidationFailed(
			message = "Document is corrupted",
		).left()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		result.statusCode shouldBe 1
		result.stderr shouldContain "Document is corrupted"
	}
	
	test("validate --json outputs structured JSON on success") {
		val input = tmpFile("signed2.pdf")
		coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()
		
		val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "\"overallResult\""
		result.output shouldContain "test.pdf"
		result.statusCode shouldBe 0
	}
	
	test("validate --json outputs JSON error with exit code 1") {
		val input = tmpFile("bad2.pdf")
		coEvery { validationRepository.validateDocument(any()) } returns ValidationError.ValidationFailed(
			message = "File not a PDF",
		).left()
		
		val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "File not a PDF"
		result.statusCode shouldBe 1
	}
	
	test("qualification errors are shown under Qualification section, not under Errors") {
		val input = tmpFile("qual.pdf")
		val reportWithQualification = sampleReport.copy(
			signatures = listOf(
				sampleReport.signatures.first().copy(
					qualificationErrors = listOf("Unable to build a certificate chain up to a trusted list!"),
					qualificationWarnings = listOf("The signing certificate does not have an expected key-usage!"),
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns reportWithQualification.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		result.output shouldContain "Qualification"
		result.output shouldContain "Unable to build a certificate chain"
		result.output shouldContain "expected key-usage"
		val errorsIdx = result.output.indexOf("❌ Errors:")
		errorsIdx shouldBe -1
		result.statusCode shouldBe 0
	}
	
	test("trust tier is shown for qualified signatures") {
		val input = tmpFile("qualified.pdf")
		val qualifiedReport = sampleReport.copy(
			signatures = listOf(
				sampleReport.signatures.first().copy(
					trustTier = SignatureTrustTier.QUALIFIED_QSCD,
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns qualifiedReport.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		result.output shouldContain "Trust tier:"
		result.output shouldContain "Qualified (QSCD)"
		result.statusCode shouldBe 0
	}
	
	test("trust tier is omitted for non-qualified signatures") {
		val input = tmpFile("nonqual.pdf")
		coEvery { validationRepository.validateDocument(any()) } returns sampleReport.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		result.output shouldNotContain "Trust tier:"
		result.statusCode shouldBe 0
	}
	
	test("trust tier appears in JSON output") {
		val input = tmpFile("jsontier.pdf")
		val qualifiedReport = sampleReport.copy(
			signatures = listOf(
				sampleReport.signatures.first().copy(
					trustTier = SignatureTrustTier.QUALIFIED,
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns qualifiedReport.right()
		
		val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))
		
		result.output shouldContain "\"trustTier\":\"QUALIFIED\""
		result.statusCode shouldBe 0
	}
	
	test("overall trust tier is shown in header for valid qualified document") {
		val input = tmpFile("overalltier.pdf")
		val qualifiedReport = sampleReport.copy(
			signatures = listOf(
				sampleReport.signatures.first().copy(
					trustTier = SignatureTrustTier.QUALIFIED_QSCD,
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns qualifiedReport.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		val separator = "═".repeat(63)
		val firstEnd = result.output.indexOf(separator)
		val secondStart = result.output.indexOf(separator, firstEnd + separator.length)
		val thirdStart = result.output.indexOf(separator, secondStart + separator.length)
		val header = result.output.substring(0, thirdStart)
		header shouldContain "Trust tier:"
		header shouldContain "Qualified (QSCD)"
		result.statusCode shouldBe 0
	}
	
	test("overall trust tier is suppressed when document is indeterminate even if signature is qualified") {
		val input = tmpFile("indettier.pdf")
		val indeterminateReport = sampleReport.copy(
			overallResult = ValidationResult.INDETERMINATE,
			signatures = listOf(
				sampleReport.signatures.first().copy(
					indication = ValidationIndication.INDETERMINATE,
					trustTier = SignatureTrustTier.QUALIFIED,
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns indeterminateReport.right()
		
		val result = Omnisign().test(listOf("validate", "-f", input.absolutePath))
		
		val separator = "═".repeat(63)
		val firstEnd = result.output.indexOf(separator)
		val secondStart = result.output.indexOf(separator, firstEnd + separator.length)
		val thirdStart = result.output.indexOf(separator, secondStart + separator.length)
		val header = result.output.substring(0, thirdStart)
		header shouldNotContain "Trust tier:"
		result.statusCode shouldBe 0
	}
	
	test("overall trust tier appears in JSON output for valid qualified document") {
		val input = tmpFile("jsonoverall.pdf")
		val qualifiedReport = sampleReport.copy(
			signatures = listOf(
				sampleReport.signatures.first().copy(
					trustTier = SignatureTrustTier.QUALIFIED_QSCD,
				)
			)
		)
		coEvery { validationRepository.validateDocument(any()) } returns qualifiedReport.right()
		
		val result = Omnisign().test(listOf("--json", "validate", "-f", input.absolutePath))
		
		result.output shouldContain "\"overallTrustTier\":\"QUALIFIED_QSCD\""
		result.statusCode shouldBe 0
	}
})

