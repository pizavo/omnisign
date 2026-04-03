package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
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
 * Verifies [GetDocumentTimestampInfoUseCase] delegation to
 * [ArchivingRepository.getDocumentTimestampInfo].
 */
class GetDocumentTimestampInfoUseCaseTest : FunSpec({

	val repo: ArchivingRepository = mockk()
	val useCase = GetDocumentTimestampInfoUseCase(repo)

	val filePath = "/tmp/signed.pdf"

	beforeTest { clearMocks(repo) }

	test("returns timestamp info on success") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = true, containsLtData = true)
		coEvery { repo.getDocumentTimestampInfo(filePath) } returns info.right()

		useCase(filePath).shouldBeRight() shouldBe info
	}

	test("returns info for unsigned document") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
		coEvery { repo.getDocumentTimestampInfo(filePath) } returns info.right()

		val result = useCase(filePath).shouldBeRight()
		result.hasDocumentTimestamp shouldBe false
		result.containsLtData shouldBe false
	}

	test("propagates error from repository") {
		coEvery { repo.getDocumentTimestampInfo(filePath) } returns
			ArchivingError.ExtensionFailed(message = "Cannot read file").left()

		useCase(filePath).shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
			.message shouldBe "Cannot read file"
	}

	test("forwards exact file path to repository") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = true)
		coEvery { repo.getDocumentTimestampInfo(any()) } returns info.right()

		useCase(filePath)
		coVerify(exactly = 1) { repo.getDocumentTimestampInfo(filePath) }
	}
})
