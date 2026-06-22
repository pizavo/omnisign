package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.text.LocalizableText
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

	val inputBytes = byteArrayOf(1, 2, 3)

	beforeTest { clearMocks(repo) }

	test("returns timestamp info on success") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = true, containsLtData = true)
		coEvery { repo.getDocumentTimestampInfo(inputBytes) } returns info.right()

		useCase(inputBytes).shouldBeRight() shouldBe info
	}

	test("returns info for unsigned document") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
		coEvery { repo.getDocumentTimestampInfo(inputBytes) } returns info.right()

		val result = useCase(inputBytes).shouldBeRight()
		result.hasDocumentTimestamp shouldBe false
		result.containsLtData shouldBe false
	}

	test("propagates error from repository") {
		coEvery { repo.getDocumentTimestampInfo(inputBytes) } returns
			ArchivingError.ExtensionFailed(text = LocalizableText.Literal("Cannot read file")).left()

		useCase(inputBytes).shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
			.message shouldBe "Cannot read file"
	}

	test("forwards exact bytes to repository") {
		val info = DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = true)
		coEvery { repo.getDocumentTimestampInfo(any()) } returns info.right()

		useCase(inputBytes)
		coVerify(exactly = 1) { repo.getDocumentTimestampInfo(inputBytes) }
	}
})
