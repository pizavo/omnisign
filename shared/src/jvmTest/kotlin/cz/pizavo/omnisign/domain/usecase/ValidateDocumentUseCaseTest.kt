package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.time.Instant

/**
 * Verifies [ValidateDocumentUseCase] delegation and result propagation.
 */
class ValidateDocumentUseCaseTest : FunSpec({

	val repo: ValidationRepository = mockk()
	val useCase = ValidateDocumentUseCase(repo)
	val params = ValidationParameters(inputFile = "/tmp/signed.pdf")

	beforeTest { clearMocks(repo) }

	val report = ValidationReport(
		documentName = "signed.pdf",
		validationTime = Instant.parse("2026-03-14T10:00:00Z"),
		overallResult = ValidationResult.VALID,
		signatures = listOf(
			SignatureValidationResult(
				signatureId = "sig-1",
				indication = ValidationIndication.TOTAL_PASSED,
				signedBy = "Signer",
				signatureLevel = "PAdES-BASELINE-T",
				signatureTime = Instant.parse("2026-03-14T09:00:00Z"),
				certificate = CertificateInfo(
					subjectDN = "CN=Test", issuerDN = "CN=CA",
					serialNumber = "1234",
					validFrom = Instant.parse("2025-01-01T00:00:00Z"),
					validTo = Instant.parse("2027-01-01T00:00:00Z"),
				),
			)
		),
	)

	test("returns report on success") {
		coEvery { repo.validateDocument(params) } returns report.right()
		useCase(params).shouldBeRight() shouldBe report
	}

	test("propagates validation failure") {
		coEvery { repo.validateDocument(params) } returns
			ValidationError.ValidationFailed(message = "corrupted").left()
		useCase(params).shouldBeLeft().shouldBeInstanceOf<ValidationError.ValidationFailed>()
	}

	test("propagates invalid document error") {
		coEvery { repo.validateDocument(params) } returns
			ValidationError.InvalidDocument(message = "Not PDF", details = "bad magic").left()
		val err = useCase(params).shouldBeLeft().shouldBeInstanceOf<ValidationError.InvalidDocument>()
		err.details shouldBe "bad magic"
	}

	test("forwards exact parameters") {
		coEvery { repo.validateDocument(any()) } returns report.right()
		useCase(params).shouldBeRight()
		coVerify(exactly = 1) { repo.validateDocument(params) }
	}
})
