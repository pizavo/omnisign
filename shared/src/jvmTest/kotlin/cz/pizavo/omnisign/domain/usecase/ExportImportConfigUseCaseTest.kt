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
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [ExportImportConfigUseCase].
 *
 * Verifies that the use case correctly delegates to the [ConfigSerializerRegistry],
 * merges imported data into the existing configuration, and propagates errors.
 */
class ExportImportConfigUseCaseTest {
	
	private val configRepository: ConfigRepository = mockk()
	private val jsonSerializer = JsonConfigSerializer()
	private val registry = ConfigSerializerRegistry(listOf(jsonSerializer))
	private val useCase = ExportImportConfigUseCase(configRepository, registry)
	
	private val baseGlobal = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA256)
	private val baseProfile = ProfileConfig(name = "p1", hashAlgorithm = HashAlgorithm.SHA512)
	private val baseConfig = AppConfig(
		global = baseGlobal,
		profiles = mapOf("p1" to baseProfile),
		activeProfile = "p1"
	)
	
	@Test
	fun `exportGlobal returns serialized global config`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.exportGlobal(ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<String>>(result)
		assertTrue(result.value.contains("SHA256"))
	}
	
	@Test
	fun `exportApp returns serialized full config`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.exportApp(ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<String>>(result)
		assertTrue(result.value.contains("p1"))
	}
	
	@Test
	fun `importGlobal updates only the global section`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newGlobal = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)
		val serialized = (jsonSerializer.serializeGlobal(newGlobal) as arrow.core.Either.Right).value
		
		val result = useCase.importGlobal(serialized, ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<Unit>>(result)
		assertEquals(HashAlgorithm.SHA512, savedSlot.captured.global.defaultHashAlgorithm)
		assertEquals("p1", savedSlot.captured.activeProfile, "profiles must be preserved")
		assertEquals(1, savedSlot.captured.profiles.size, "profiles must be preserved")
	}
	
	@Test
	fun `importApp replaces the entire config`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newConfig = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384))
		val serialized = (jsonSerializer.serializeApp(newConfig) as arrow.core.Either.Right).value
		
		val result = useCase.importApp(serialized, ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<Unit>>(result)
		assertEquals(HashAlgorithm.SHA384, savedSlot.captured.global.defaultHashAlgorithm)
		assertTrue(savedSlot.captured.profiles.isEmpty(), "profiles should be empty in new config")
	}
	
	@Test
	fun `exportProfile returns serialized profile`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.exportProfile("p1", ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<String>>(result)
		assertTrue(result.value.contains("p1"))
	}
	
	@Test
	fun `exportProfile returns error for unknown profile`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = useCase.exportProfile("nonexistent", ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Left<ConfigurationError>>(result)
		assertTrue(result.value.message.contains("nonexistent"))
	}
	
	@Test
	fun `importProfile upserts profile using name from file`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newProfile = ProfileConfig(name = "imported", hashAlgorithm = HashAlgorithm.SHA384)
		val serialized = (jsonSerializer.serializeProfile(newProfile) as arrow.core.Either.Right).value
		
		val result = useCase.importProfile(serialized, ConfigFormat.JSON)
		
		assertIs<arrow.core.Either.Right<String>>(result)
		assertEquals("imported", result.value)
		assertTrue(savedSlot.captured.profiles.containsKey("imported"))
		assertEquals(2, savedSlot.captured.profiles.size, "existing profile p1 must be preserved")
	}
	
	@Test
	fun `importProfile respects overrideName`() = runBlocking {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val savedSlot = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(savedSlot)) } returns Unit.right()
		
		val newProfile = ProfileConfig(name = "imported", hashAlgorithm = HashAlgorithm.SHA384)
		val serialized = (jsonSerializer.serializeProfile(newProfile) as arrow.core.Either.Right).value
		
		val result = useCase.importProfile(serialized, ConfigFormat.JSON, overrideName = "renamed")
		
		assertIs<arrow.core.Either.Right<String>>(result)
		assertEquals("renamed", result.value)
		assertTrue(savedSlot.captured.profiles.containsKey("renamed"))
	}
	
	@Test
	fun `returns error when no serializer registered for format`() = runBlocking {
		val emptyRegistry = ConfigSerializerRegistry(emptyList())
		val emptyUseCase = ExportImportConfigUseCase(configRepository, emptyRegistry)
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		val result = emptyUseCase.exportGlobal(ConfigFormat.YAML)
		
		assertIs<arrow.core.Either.Left<ConfigurationError>>(result)
		assertTrue(result.value.message.contains("YAML"))
	}
}

