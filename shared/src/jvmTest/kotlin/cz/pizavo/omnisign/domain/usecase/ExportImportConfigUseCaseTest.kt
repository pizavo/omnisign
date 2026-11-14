package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ExportImportConfigUseCase] delegation to [ConfigSerializerRegistry],
 * merge logic, and error propagation, and that an import is refused, with nothing saved, when a
 * default or override it carries is among the algorithms the same layer disables.
 */
class ExportImportConfigUseCaseTest : FunSpec({
	
	val configRepository: ConfigRepository = mockk()
	val jsonSerializer = JsonConfigSerializer()
	val registry = ConfigSerializerRegistry(listOf(jsonSerializer))
	val useCase = ExportImportConfigUseCase(configRepository, registry)
	
	val baseGlobal = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA256)
	val baseProfile = ProfileConfig(name = "p1", hashAlgorithm = HashAlgorithm.SHA512)
	val baseConfig = AppConfig(
		global = baseGlobal,
		profiles = mapOf("p1" to baseProfile),
		activeProfile = "p1"
	)
	
	test("exportGlobal returns serialized global config") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.exportGlobal(ConfigFormat.JSON).shouldBeRight() shouldContain "SHA256"
	}
	
	test("exportApp returns serialized full config") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.exportApp(ConfigFormat.JSON).shouldBeRight() shouldContain "p1"
	}
	
	test("importGlobal updates only the global section") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newGlobal = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)
		val serialized = jsonSerializer.serializeGlobal(newGlobal).shouldBeRight()
		
		useCase.importGlobal(serialized, ConfigFormat.JSON).shouldBeRight()
		savedSlot.captured.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
		savedSlot.captured.activeProfile shouldBe "p1"
		savedSlot.captured.profiles.shouldHaveSize(1)
	}
	
	test("importApp replaces the entire config") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newConfig = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384))
		val serialized = jsonSerializer.serializeApp(newConfig).shouldBeRight()
		
		useCase.importApp(serialized, ConfigFormat.JSON).shouldBeRight()
		savedSlot.captured.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
		savedSlot.captured.profiles.shouldBeEmpty()
	}
	
	test("exportProfile returns serialized profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.exportProfile("p1", ConfigFormat.JSON).shouldBeRight() shouldContain "p1"
	}
	
	test("exportProfile returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.exportProfile("nonexistent", ConfigFormat.JSON)
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError>()
			.message shouldContain "nonexistent"
	}
	
	test("importProfile upserts profile using name from file") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newProfile = ProfileConfig(name = "imported", hashAlgorithm = HashAlgorithm.SHA384)
		val serialized = jsonSerializer.serializeProfile(newProfile).shouldBeRight()
		
		useCase.importProfile(serialized, ConfigFormat.JSON).shouldBeRight() shouldBe "imported"
		savedSlot.captured.profiles.shouldContainKey("imported")
		savedSlot.captured.profiles.shouldHaveSize(2)
	}
	
	test("importProfile respects overrideName") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newProfile = ProfileConfig(name = "imported", hashAlgorithm = HashAlgorithm.SHA384)
		val serialized = jsonSerializer.serializeProfile(newProfile).shouldBeRight()
		
		useCase.importProfile(serialized, ConfigFormat.JSON, overrideName = "renamed")
			.shouldBeRight() shouldBe "renamed"
		savedSlot.captured.profiles.shouldContainKey("renamed")
	}
	
	test("returns error when no serializer registered for format") {
		val emptyRegistry = ConfigSerializerRegistry(emptyList())
		val emptyUseCase = ExportImportConfigUseCase(configRepository, emptyRegistry)
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		emptyUseCase.exportGlobal(ConfigFormat.YAML)
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError>()
			.message shouldContain "YAML"
	}

	test("importGlobal refuses a global section whose default hash algorithm it disables, and saves nothing") {
		val repository: ConfigRepository = mockk()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384, disabledHashAlgorithms = setOf(HashAlgorithm.SHA384))
		val serialized = jsonSerializer.serializeGlobal(global).shouldBeRight()

		val error = ExportImportConfigUseCase(repository, registry).importGlobal(serialized, ConfigFormat.JSON).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_HASH, "SHA384")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	test("importApp refuses a configuration whose global default encryption algorithm it disables, and saves nothing") {
		val repository: ConfigRepository = mockk()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val config = AppConfig(
			global = GlobalConfig(
				defaultEncryptionAlgorithm = EncryptionAlgorithm.ECDSA,
				disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.ECDSA),
			),
		)
		val serialized = jsonSerializer.serializeApp(config).shouldBeRight()

		val error = ExportImportConfigUseCase(repository, registry).importApp(serialized, ConfigFormat.JSON).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_ENCRYPTION, "ECDSA")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	test("importApp refuses a configuration with a profile that disables its own hash override, and saves nothing") {
		val repository: ConfigRepository = mockk()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val config = AppConfig(
			profiles = mapOf(
				"fine" to ProfileConfig(name = "fine", hashAlgorithm = HashAlgorithm.SHA384),
				"broken" to ProfileConfig(
					name = "broken",
					hashAlgorithm = HashAlgorithm.SHA512,
					disabledHashAlgorithms = setOf(HashAlgorithm.SHA512),
				),
			),
		)
		val serialized = jsonSerializer.serializeApp(config).shouldBeRight()

		val error = ExportImportConfigUseCase(repository, registry).importApp(serialized, ConfigFormat.JSON).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH, "broken", "SHA512")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	test("importProfile refuses a profile that disables its own encryption override, naming it as it would be saved") {
		val repository: ConfigRepository = mockk()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val profile = ProfileConfig(
			name = "imported",
			encryptionAlgorithm = EncryptionAlgorithm.RSA,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.RSA),
		)
		val serialized = jsonSerializer.serializeProfile(profile).shouldBeRight()

		val error = ExportImportConfigUseCase(repository, registry)
			.importProfile(serialized, ConfigFormat.JSON, overrideName = "renamed").shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION, "renamed", "RSA")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}
})
