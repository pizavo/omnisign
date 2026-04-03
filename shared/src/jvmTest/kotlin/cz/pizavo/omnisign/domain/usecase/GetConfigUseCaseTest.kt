package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
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
 * Verifies [GetConfigUseCase] delegation to [ConfigRepository.loadConfig].
 */
class GetConfigUseCaseTest : FunSpec({

	val repo: ConfigRepository = mockk()
	val useCase = GetConfigUseCase(repo)

	beforeTest { clearMocks(repo) }

	test("returns config on success") {
		val config = AppConfig(global = GlobalConfig())
		coEvery { repo.loadConfig() } returns config.right()

		useCase().shouldBeRight() shouldBe config
	}

	test("propagates load failure") {
		coEvery { repo.loadConfig() } returns
			ConfigurationError.LoadFailed(message = "Corrupt JSON").left()

		useCase().shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.LoadFailed>()
			.message shouldBe "Corrupt JSON"
	}

	test("delegates exactly once") {
		val config = AppConfig(global = GlobalConfig())
		coEvery { repo.loadConfig() } returns config.right()

		useCase()
		coVerify(exactly = 1) { repo.loadConfig() }
	}
})
