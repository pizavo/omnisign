package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
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
 * Verifies [ExtendDocumentUseCase] delegation to [ArchivingRepository.extendDocument].
 */
class ExtendDocumentUseCaseTest : FunSpec({

	val repo: ArchivingRepository = mockk()
	val useCase = ExtendDocumentUseCase(repo)

	val params = ArchivingParameters(
		inputFile = "/tmp/signed.pdf",
		outputFile = "/tmp/extended.pdf",
		targetLevel = SignatureLevel.PADES_BASELINE_LTA,
	)

	beforeTest { clearMocks(repo) }

	test("returns archiving result on success") {
		val expected = ArchivingResult(
			outputFile = "/tmp/extended.pdf",
			newSignatureLevel = "PAdES-BASELINE-LTA",
		)
		coEvery { repo.extendDocument(params) } returns expected.right()

		useCase(params).shouldBeRight() shouldBe expected
	}

	test("propagates extension failure") {
		coEvery { repo.extendDocument(params) } returns
			ArchivingError.ExtensionFailed(message = "Cannot extend").left()

		useCase(params).shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
			.message shouldBe "Cannot extend"
	}

	test("propagates timestamp failure") {
		coEvery { repo.extendDocument(params) } returns
			ArchivingError.TimestampFailed(message = "TSA unreachable", details = "Connection refused").left()

		val error = useCase(params).shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.TimestampFailed>()
		error.message shouldBe "TSA unreachable"
		error.details shouldBe "Connection refused"
	}

	test("forwards exact parameters to repository") {
		coEvery { repo.extendDocument(any()) } returns ArchivingResult(
			outputFile = "/tmp/extended.pdf",
			newSignatureLevel = "PAdES-BASELINE-T",
		).right()

		useCase(params).shouldBeRight()
		coVerify(exactly = 1) { repo.extendDocument(params) }
	}
})
