package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

/**
 * Verifies [ExportImportConfigUseCase] delegation to [ConfigSerializerRegistry],
 * merge logic, and error propagation, and that an import is refused, with nothing saved, when a
 * default or override it carries is among the algorithms the same layer disables. An import that adds a
 * profile drops every trust scope no profile owns before it saves, one that drops a profile drops its
 * scope once it has saved, and one that keeps the stored profiles leaves the trust store alone.
 */
class ExportImportConfigUseCaseTest : FunSpec({
	
	val configRepository: ConfigRepository = mockk()
	val jsonSerializer = JsonConfigSerializer()
	val registry = ConfigSerializerRegistry(listOf(jsonSerializer))
	val trustStore: TrustStore = mockk {
		every { readOnly } returns false
		coEvery { scopes() } returns emptySet<TrustScope>().right()
	}
	val useCase = ExportImportConfigUseCase(configRepository, registry, trustStore)
	
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
		val emptyUseCase = ExportImportConfigUseCase(configRepository, emptyRegistry, trustStore)
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

		val error = ExportImportConfigUseCase(repository, registry, trustStore).importGlobal(serialized, ConfigFormat.JSON).shouldBeLeft()

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

		val error = ExportImportConfigUseCase(repository, registry, trustStore).importApp(serialized, ConfigFormat.JSON).shouldBeLeft()

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

		val error = ExportImportConfigUseCase(repository, registry, trustStore).importApp(serialized, ConfigFormat.JSON).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH, "broken", "SHA512")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	test("importProfile refuses a profile that disables its own hash override, and saves nothing") {
		val repository: ConfigRepository = mockk()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val profile = ProfileConfig(
			name = "imported",
			hashAlgorithm = HashAlgorithm.SHA512,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA512),
		)
		val serialized = jsonSerializer.serializeProfile(profile).shouldBeRight()

		val error = ExportImportConfigUseCase(repository, registry, trustStore).importProfile(serialized, ConfigFormat.JSON).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH, "imported", "SHA512")
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

		val error = ExportImportConfigUseCase(repository, registry, trustStore)
			.importProfile(serialized, ConfigFormat.JSON, overrideName = "renamed").shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION, "renamed", "RSA")
		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	/**
	 * Return a trust store that holds the global scope and the scopes of [profiles], and drops a profile's
	 * scope on request.
	 */
	fun storeWithScopes(vararg profiles: String): TrustStore = mockk {
		every { readOnly } returns false
		coEvery { scopes() } returns (profiles.map { TrustScope.Profile(it) } + TrustScope.Global).toSet().right()
		coEvery { clearProfileScope(any()) } returns Unit.right()
	}

	test("importApp drops the trust scope of a stored profile the imported configuration leaves out, once it is saved") {
		val repository: ConfigRepository = mockk()
		val store = storeWithScopes("p1", "p2")
		coEvery { repository.getCurrentConfig() } returns
			baseConfig.copy(profiles = mapOf("p1" to baseProfile, "p2" to baseProfile.copy(name = "p2")))
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val serialized = jsonSerializer.serializeApp(baseConfig).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importApp(serialized, ConfigFormat.JSON).shouldBeRight()

		coVerifyOrder {
			repository.saveConfig(match { "p2" !in it.profiles })
			store.clearProfileScope("p2")
		}
		coVerify(exactly = 1) { store.clearProfileScope("p2") }
		coVerify(exactly = 0) { store.clearProfileScope("p1") }
	}

	test("importApp drops a leftover trust scope of a profile it adds before it saves the configuration") {
		val repository: ConfigRepository = mockk()
		val store = storeWithScopes("p1", "added")
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val imported = baseConfig.copy(profiles = baseConfig.profiles + ("added" to ProfileConfig(name = "added")))
		val serialized = jsonSerializer.serializeApp(imported).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importApp(serialized, ConfigFormat.JSON).shouldBeRight()

		coVerifyOrder {
			store.clearProfileScope("added")
			repository.saveConfig(match { "added" in it.profiles })
		}
		coVerify(exactly = 1) { store.clearProfileScope("added") }
		coVerify(exactly = 0) { store.clearProfileScope("p1") }
	}

	test("importApp saves nothing when a leftover trust scope of a profile it adds cannot be dropped") {
		val repository: ConfigRepository = mockk()
		val failure = TrustStoreError.operationFailed(details = "index not writable")
		val store = storeWithScopes("added")
		coEvery { store.clearProfileScope("added") } returns failure.left()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		val imported = baseConfig.copy(profiles = baseConfig.profiles + ("added" to ProfileConfig(name = "added")))
		val serialized = jsonSerializer.serializeApp(imported).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importApp(serialized, ConfigFormat.JSON)
			.shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}

	test("importApp of the stored profiles leaves the trust store alone") {
		val repository: ConfigRepository = mockk()
		val store = storeWithScopes("p1", "gone")
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val serialized = jsonSerializer.serializeApp(baseConfig.copy(global = GlobalConfig())).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importApp(serialized, ConfigFormat.JSON).shouldBeRight()

		coVerify(exactly = 0) { store.scopes() }
		coVerify(exactly = 0) { store.clearProfileScope(any()) }
	}

	test("importApp leaves the trust store alone when the configuration that drops a profile cannot be saved") {
		val repository: ConfigRepository = mockk()
		val store = storeWithScopes("p1", "p2")
		val failure = ConfigurationError.saveFailed(details = "disk full")
		coEvery { repository.getCurrentConfig() } returns
			baseConfig.copy(profiles = mapOf("p1" to baseProfile, "p2" to baseProfile.copy(name = "p2")))
		coEvery { repository.saveConfig(any()) } returns failure.left()
		val serialized = jsonSerializer.serializeApp(baseConfig).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importApp(serialized, ConfigFormat.JSON)
			.shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { store.clearProfileScope(any()) }
	}

	test("importProfile under a new name drops every trust scope no profile owns before it saves, and under a stored name leaves the trust store alone") {
		val repository: ConfigRepository = mockk()
		val store = storeWithScopes("p1", "imported", "gone")
		coEvery { repository.getCurrentConfig() } returns baseConfig
		coEvery { repository.saveConfig(any()) } returns Unit.right()
		val serialized = jsonSerializer.serializeProfile(ProfileConfig(name = "imported")).shouldBeRight()
		val imports = ExportImportConfigUseCase(repository, registry, store)

		imports.importProfile(serialized, ConfigFormat.JSON).shouldBeRight()

		coVerifyOrder {
			store.clearProfileScope("imported")
			repository.saveConfig(match { "imported" in it.profiles })
		}
		coVerify { store.clearProfileScope("gone") }
		coVerify(exactly = 0) { store.clearProfileScope("p1") }

		imports.importProfile(serialized, ConfigFormat.JSON, overrideName = "p1").shouldBeRight()

		coVerify(exactly = 1) { store.scopes() }
	}

	test("importProfile under a new name saves nothing when a leftover trust scope cannot be dropped") {
		val repository: ConfigRepository = mockk()
		val failure = TrustStoreError.operationFailed(details = "index not writable")
		val store = storeWithScopes("imported")
		coEvery { store.clearProfileScope("imported") } returns failure.left()
		coEvery { repository.getCurrentConfig() } returns baseConfig
		val serialized = jsonSerializer.serializeProfile(ProfileConfig(name = "imported")).shouldBeRight()

		ExportImportConfigUseCase(repository, registry, store).importProfile(serialized, ConfigFormat.JSON)
			.shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { repository.saveConfig(any()) }
	}
})
