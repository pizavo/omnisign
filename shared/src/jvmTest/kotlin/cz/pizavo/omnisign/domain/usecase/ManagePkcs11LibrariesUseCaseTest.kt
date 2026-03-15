package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ManagePkcs11LibrariesUseCase] CRUD operations and error handling.
 */
class ManagePkcs11LibrariesUseCaseTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val useCase = ManagePkcs11LibrariesUseCase(configRepository)

	val baseConfig = AppConfig()

	beforeTest { clearMocks(configRepository) }

	fun lib(name: String, path: String = "/usr/lib/$name.so") =
		CustomPkcs11Library(name = name, path = path)

	test("addLibrary stores a new library entry") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.addLibrary(lib("opensc")).shouldBeRight()

		saved.captured.global.customPkcs11Libraries.shouldHaveSize(1)
		saved.captured.global.customPkcs11Libraries.first().name shouldBe "opensc"
	}

	test("addLibrary replaces entry with same name") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				customPkcs11Libraries = listOf(lib("opensc", "/old/path.so"))
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.addLibrary(lib("opensc", "/new/path.so")).shouldBeRight()

		saved.captured.global.customPkcs11Libraries.shouldHaveSize(1)
		saved.captured.global.customPkcs11Libraries.first().path shouldBe "/new/path.so"
	}

	test("addLibrary preserves other entries") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				customPkcs11Libraries = listOf(lib("other"))
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.addLibrary(lib("opensc")).shouldBeRight()

		saved.captured.global.customPkcs11Libraries.shouldHaveSize(2)
	}

	test("removeLibrary deletes existing entry") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				customPkcs11Libraries = listOf(lib("opensc"), lib("safenet"))
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.removeLibrary("opensc").shouldBeRight()

		saved.captured.global.customPkcs11Libraries.shouldHaveSize(1)
		saved.captured.global.customPkcs11Libraries.first().name shouldBe "safenet"
	}

	test("removeLibrary returns error for unknown name") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.removeLibrary("ghost").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()

		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("listLibraries returns all registered entries") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				customPkcs11Libraries = listOf(lib("a"), lib("b"))
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		val libs = useCase.listLibraries().shouldBeRight()
		libs.shouldHaveSize(2)
		libs.map { it.name } shouldBe listOf("a", "b")
	}

	test("listLibraries returns empty list when none registered") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.listLibraries().shouldBeRight().shouldBeEmpty()
	}
})


