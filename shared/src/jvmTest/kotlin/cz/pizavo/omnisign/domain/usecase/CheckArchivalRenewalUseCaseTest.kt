package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ArchivingRepository.Companion.DEFAULT_RENEWAL_BUFFER_DAYS
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
 * Verifies [CheckArchivalRenewalUseCase] delegation to [ArchivingRepository.needsArchivalRenewal].
 */
class CheckArchivalRenewalUseCaseTest : FunSpec({

	val repo: ArchivingRepository = mockk()
	val useCase = CheckArchivalRenewalUseCase(repo)

	val filePath = "/tmp/archive.pdf"

	beforeTest { clearMocks(repo) }

	test("returns true when renewal is needed") {
		coEvery { repo.needsArchivalRenewal(filePath, DEFAULT_RENEWAL_BUFFER_DAYS) } returns true.right()

		useCase(filePath).shouldBeRight() shouldBe true
	}

	test("returns false when renewal is not needed") {
		coEvery { repo.needsArchivalRenewal(filePath, DEFAULT_RENEWAL_BUFFER_DAYS) } returns false.right()

		useCase(filePath).shouldBeRight() shouldBe false
	}

	test("forwards custom renewal buffer days") {
		coEvery { repo.needsArchivalRenewal(filePath, 30) } returns true.right()

		useCase(filePath, renewalBufferDays = 30).shouldBeRight() shouldBe true
		coVerify(exactly = 1) { repo.needsArchivalRenewal(filePath, 30) }
	}

	test("propagates error from repository") {
		coEvery { repo.needsArchivalRenewal(filePath, DEFAULT_RENEWAL_BUFFER_DAYS) } returns
			ArchivingError.ExtensionFailed(message = "File not found").left()

		useCase(filePath).shouldBeLeft()
			.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
			.message shouldBe "File not found"
	}

	test("uses default buffer days when not specified") {
		coEvery { repo.needsArchivalRenewal(any(), any()) } returns false.right()

		useCase(filePath)
		coVerify(exactly = 1) { repo.needsArchivalRenewal(filePath, DEFAULT_RENEWAL_BUFFER_DAYS) }
	}
})
