package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
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
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ExportImportConfigUseCase] delegation to [ConfigSerializerRegistry],
 * merge logic, and error propagation.
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
})

