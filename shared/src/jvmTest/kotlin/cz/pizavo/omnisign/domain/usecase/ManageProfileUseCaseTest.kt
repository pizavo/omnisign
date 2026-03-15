package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ManageProfileUseCase] CRUD operations, active-profile management,
 * and self-disabling algorithm rejection.
 */
class ManageProfileUseCaseTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val useCase = ManageProfileUseCase(configRepository)

	val baseConfig = AppConfig()

	beforeTest { clearMocks(configRepository) }

	fun profile(
		name: String,
		hash: HashAlgorithm? = null,
		enc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = ProfileConfig(
		name = name,
		hashAlgorithm = hash,
		encryptionAlgorithm = enc,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)

	test("upsert saves a new profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsert(profile("p1")).shouldBeRight()

		saved.captured.profiles.shouldHaveSize(1)
		saved.captured.profiles.shouldContainKey("p1")
	}

	test("upsert replaces an existing profile") {
		val existing = baseConfig.copy(
			profiles = mapOf("p1" to profile("p1", hash = HashAlgorithm.SHA256))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsert(profile("p1", hash = HashAlgorithm.SHA512)).shouldBeRight()

		saved.captured.profiles["p1"]?.hashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("upsert rejects profile that disables its own hash algorithm override") {
		useCase.upsert(
			profile("bad", hash = HashAlgorithm.SHA384, disabledHash = setOf(HashAlgorithm.SHA384))
		).shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "SHA384"
	}

	test("upsert rejects profile that disables its own encryption algorithm override") {
		useCase.upsert(
			profile("bad", enc = EncryptionAlgorithm.RSA, disabledEnc = setOf(EncryptionAlgorithm.RSA))
		).shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "RSA"
	}

	test("upsert allows disabled set when override is null") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsert(
			profile("ok", hash = null, disabledHash = setOf(HashAlgorithm.RIPEMD160))
		).shouldBeRight()
	}

	test("remove deletes an existing profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.remove("p1").shouldBeRight()
		saved.captured.profiles.shouldBeEmpty()
	}

	test("remove clears activeProfile when removing the active profile") {
		val existing = baseConfig.copy(
			profiles = mapOf("p1" to profile("p1")),
			activeProfile = "p1"
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.remove("p1").shouldBeRight()
		saved.captured.activeProfile.shouldBeNull()
	}

	test("remove preserves activeProfile when removing a different profile") {
		val existing = baseConfig.copy(
			profiles = mapOf("p1" to profile("p1"), "p2" to profile("p2")),
			activeProfile = "p1"
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.remove("p2").shouldBeRight()
		saved.captured.activeProfile shouldBe "p1"
	}

	test("remove returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.remove("missing").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("setActive activates an existing profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.setActive("p1").shouldBeRight()
		saved.captured.activeProfile shouldBe "p1"
	}

	test("setActive with null clears the active profile") {
		val existing = baseConfig.copy(
			profiles = mapOf("p1" to profile("p1")),
			activeProfile = "p1"
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.setActive(null).shouldBeRight()
		saved.captured.activeProfile.shouldBeNull()
	}

	test("setActive returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.setActive("ghost").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("get returns the requested profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.get("p1").shouldBeRight().name shouldBe "p1"
	}

	test("get returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.get("missing").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("list returns all profiles") {
		val existing = baseConfig.copy(
			profiles = mapOf("a" to profile("a"), "b" to profile("b"))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.list().shouldBeRight().keys shouldBe setOf("a", "b")
	}

	test("list returns empty map when no profiles exist") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.list().shouldBeRight().shouldBeEmpty()
	}
})


