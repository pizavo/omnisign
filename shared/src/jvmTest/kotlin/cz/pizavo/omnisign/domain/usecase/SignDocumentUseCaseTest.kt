package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.SigningRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

/**
 * Verifies [SignDocumentUseCase] delegation to [SigningRepository] and
 * correct propagation of success/error results.
 */
class SignDocumentUseCaseTest : FunSpec({

	val signingRepository: SigningRepository = mockk()
	val useCase = SignDocumentUseCase(signingRepository)

	val params = SigningParameters(
		inputFile = "/tmp/input.pdf",
		outputFile = "/tmp/output.pdf",
	)

	beforeTest { clearMocks(signingRepository) }

	test("delegates to repository and returns signing result on success") {
		val expected = SigningResult(
			outputFile = "/tmp/output.pdf",
			signatureId = "sig-1",
			signatureLevel = "PAdES-BASELINE-B",
		)
		coEvery { signingRepository.signDocument(params) } returns expected.right()

		useCase(params).shouldBeRight() shouldBe expected
	}

	test("propagates signing failure from repository") {
		coEvery { signingRepository.signDocument(params) } returns SigningError.SigningFailed(
			message = "Token not found",
		).left()

		useCase(params).shouldBeLeft()
			.shouldBeInstanceOf<SigningError.SigningFailed>()
			.message shouldBe "Token not found"
	}

	test("propagates token access error from repository") {
		coEvery { signingRepository.signDocument(params) } returns SigningError.TokenAccessError(
			message = "PKCS#11 unavailable",
			details = "libpkcs11.so not found",
		).left()

		val error = useCase(params).shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
		error.message shouldBe "PKCS#11 unavailable"
		error.details shouldBe "libpkcs11.so not found"
	}

	test("forwards exact parameters to repository") {
		coEvery { signingRepository.signDocument(any()) } returns SigningResult(
			outputFile = "/tmp/output.pdf",
			signatureId = "sig-2",
			signatureLevel = "PAdES-BASELINE-T",
		).right()

		useCase(params).shouldBeRight()
		coVerify(exactly = 1) { signingRepository.signDocument(params) }
	}

	test("propagates warnings from signing result") {
		val expected = SigningResult(
			outputFile = "/tmp/output.pdf",
			signatureId = "sig-3",
			signatureLevel = "PAdES-BASELINE-LT",
			warnings = listOf("Revocation data fetch slow"),
			rawWarnings = listOf("eu.europa.esig: CRL download timeout"),
			hasRevocationWarnings = true,
		)
		coEvery { signingRepository.signDocument(params) } returns expected.right()

		val result = useCase(params).shouldBeRight()
		result.warnings shouldBe listOf("Revocation data fetch slow")
		result.hasRevocationWarnings shouldBe true
	}
})
