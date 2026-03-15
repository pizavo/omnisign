package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [SetGlobalConfigUseCase] update application, persistence,
 * and self-disabling algorithm rejection.
 */
class SetGlobalConfigUseCaseTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val useCase = SetGlobalConfigUseCase(configRepository)

	beforeTest { clearMocks(configRepository) }

	val baseConfig = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B
		)
	)

	test("invoke persists updated global config") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase { copy(defaultHashAlgorithm = HashAlgorithm.SHA512) }.shouldBeRight()

		saved.captured.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("invoke preserves non-global parts of AppConfig") {
		val configWithProfile = baseConfig.copy(activeProfile = "p1")
		coEvery { configRepository.getCurrentConfig() } returns configWithProfile
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase { copy(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T) }.shouldBeRight()

		saved.captured.activeProfile shouldBe "p1"
	}

	test("invoke rejects update that disables the default hash algorithm") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase {
			copy(disabledHashAlgorithms = setOf(HashAlgorithm.SHA256))
		}.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "SHA256"

		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("invoke rejects update that disables the default encryption algorithm") {
		val configWithEnc = baseConfig.copy(
			global = baseConfig.global.copy(defaultEncryptionAlgorithm = EncryptionAlgorithm.RSA)
		)
		coEvery { configRepository.getCurrentConfig() } returns configWithEnc

		useCase {
			copy(disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.RSA))
		}.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "RSA"
	}

	test("invoke allows disabling non-default algorithms") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase {
			copy(disabledHashAlgorithms = setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL))
		}.shouldBeRight()

		saved.captured.global.disabledHashAlgorithms shouldBe
			setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL)
	}

	test("invoke allows disabling encryption algorithms when default is null") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase {
			copy(disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA))
		}.shouldBeRight()
	}

	test("invoke allows changing default hash to a non-disabled algorithm") {
		val config = baseConfig.copy(
			global = baseConfig.global.copy(disabledHashAlgorithms = setOf(HashAlgorithm.RIPEMD160))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase { copy(defaultHashAlgorithm = HashAlgorithm.SHA384) }.shouldBeRight()

		saved.captured.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
	}
})


