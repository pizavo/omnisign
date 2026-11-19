package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies [ManageProfileUseCase] CRUD operations, active-profile management,
 * and self-disabling algorithm rejection, and that a profile's trust scope goes with it: a removal drops
 * it once the configuration is saved, a profile of a new name is saved only once every scope no profile
 * owns is gone, and a read-only trust store is left alone. A creation or removal that cannot read the
 * stored configuration drops no scope and saves nothing, a lookup then returns the read's error rather
 * than reporting the profile missing, and a profile stored under a key other than its name keeps both
 * scopes.
 */
class ManageProfileUseCaseTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val trustStore: TrustStore = mockk()
	val useCase = ManageProfileUseCase(configRepository, trustStore)

	val baseConfig = AppConfig()

	beforeTest {
		clearMocks(configRepository, trustStore)
		every { trustStore.readOnly } returns false
		coEvery { trustStore.scopes() } returns emptySet<TrustScope>().right()
	}

	/**
	 * Return the DER encoding of a new self-signed certificate whose subject's common name is [cn].
	 */
	fun selfSignedDer(cn: String): ByteArray {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val name = X500Name("CN=$cn,O=OmniSign Test")
		val now = System.currentTimeMillis()
		val builder = JcaX509v3CertificateBuilder(
			name,
			BigInteger.valueOf(now),
			Date(now - 1000L),
			Date(now + 365L * 24 * 60 * 60 * 1000),
			name,
			keyPair.public,
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		return JcaX509CertificateConverter().getCertificate(builder.build(signer)).encoded
	}

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
		coEvery { configRepository.loadConfig() } returns baseConfig.right()
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
		coEvery { configRepository.loadConfig() } returns existing.right()
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
		coEvery { configRepository.loadConfig() } returns baseConfig.right()
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsert(
			profile("ok", hash = null, disabledHash = setOf(HashAlgorithm.RIPEMD160))
		).shouldBeRight()
	}

	test("remove deletes an existing profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.loadConfig() } returns existing.right()
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
		coEvery { configRepository.loadConfig() } returns existing.right()
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
		coEvery { configRepository.loadConfig() } returns existing.right()
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.remove("p2").shouldBeRight()
		saved.captured.activeProfile shouldBe "p1"
	}

	test("remove returns error for unknown profile") {
		coEvery { configRepository.loadConfig() } returns baseConfig.right()

		useCase.remove("missing").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("setActive activates an existing profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val captured = slot<String?>()
		coEvery { configRepository.setActiveProfile(captureNullable(captured)) } returns Unit.right()

		useCase.setActive("p1").shouldBeRight()
		captured.captured shouldBe "p1"
	}

	test("setActive with null clears the active profile") {
		val existing = baseConfig.copy(
			profiles = mapOf("p1" to profile("p1")),
			activeProfile = "p1"
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val captured = slot<String?>()
		coEvery { configRepository.setActiveProfile(captureNullable(captured)) } returns Unit.right()

		useCase.setActive(null).shouldBeRight()
		captured.captured.shouldBeNull()
	}

	test("setActive returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.setActive("ghost").shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		coVerify(exactly = 0) { configRepository.setActiveProfile(any()) }
	}

	test("get returns the requested profile") {
		val existing = baseConfig.copy(profiles = mapOf("p1" to profile("p1")))
		coEvery { configRepository.loadConfig() } returns existing.right()

		useCase.get("p1").shouldBeRight().name shouldBe "p1"
	}

	test("get returns error for unknown profile") {
		coEvery { configRepository.loadConfig() } returns baseConfig.right()

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

	test("remove drops the removed profile's trust scope, and that of any other profile that is gone, once the configuration without it is saved") {
		coEvery { configRepository.loadConfig() } returns
			baseConfig.copy(profiles = mapOf("work" to profile("work"), "home" to profile("home"))).right()
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()
		coEvery { trustStore.scopes() } returns setOf(
			TrustScope.Global,
			TrustScope.Profile("work"),
			TrustScope.Profile("home"),
			TrustScope.Profile("gone"),
		).right()
		coEvery { trustStore.clearProfileScope(any()) } returns Unit.right()

		useCase.remove("work").shouldBeRight()

		coVerifyOrder {
			configRepository.saveConfig(match { "work" !in it.profiles && "home" in it.profiles })
			trustStore.clearProfileScope("work")
		}
		coVerify { trustStore.clearProfileScope("gone") }
		coVerify(exactly = 0) { trustStore.clearProfileScope("home") }
	}

	test("remove leaves the trust store alone when the configuration cannot be saved") {
		coEvery { configRepository.loadConfig() } returns baseConfig.copy(profiles = mapOf("work" to profile("work"))).right()
		val failure = ConfigurationError.saveFailed(details = "disk full")
		coEvery { configRepository.saveConfig(any()) } returns failure.left()

		useCase.remove("work").shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { trustStore.scopes() }
		coVerify(exactly = 0) { trustStore.clearProfileScope(any()) }
	}

	test("remove returns the trust store's error, with the profile removed, when its scope cannot be dropped") {
		coEvery { configRepository.loadConfig() } returns baseConfig.copy(profiles = mapOf("work" to profile("work"))).right()
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		coEvery { trustStore.scopes() } returns setOf<TrustScope>(TrustScope.Profile("work")).right()
		val failure = TrustStoreError.operationFailed(details = "index not writable")
		coEvery { trustStore.clearProfileScope("work") } returns failure.left()

		useCase.remove("work").shouldBeLeft() shouldBe failure

		saved.captured.profiles.shouldBeEmpty()
	}

	test("upsert of a new name drops every trust scope no profile owns, that name's among them, before it saves the profile") {
		coEvery { configRepository.loadConfig() } returns baseConfig.copy(profiles = mapOf("home" to profile("home"))).right()
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()
		coEvery { trustStore.scopes() } returns setOf(
			TrustScope.Global,
			TrustScope.Profile("home"),
			TrustScope.Profile("work"),
			TrustScope.Profile("gone"),
		).right()
		coEvery { trustStore.clearProfileScope(any()) } returns Unit.right()

		useCase.upsert(profile("work")).shouldBeRight()

		coVerifyOrder {
			trustStore.clearProfileScope("work")
			configRepository.saveConfig(match { "work" in it.profiles })
		}
		coVerify { trustStore.clearProfileScope("gone") }
		coVerify(exactly = 0) { trustStore.clearProfileScope("home") }
	}

	test("upsert of a new name saves nothing when a leftover trust scope cannot be dropped") {
		coEvery { configRepository.loadConfig() } returns baseConfig.right()
		coEvery { trustStore.scopes() } returns setOf<TrustScope>(TrustScope.Profile("work")).right()
		val failure = TrustStoreError.operationFailed(details = "index not writable")
		coEvery { trustStore.clearProfileScope("work") } returns failure.left()

		useCase.upsert(profile("work")).shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("upsert of a new name saves nothing when the trust store cannot list its scopes") {
		coEvery { configRepository.loadConfig() } returns baseConfig.right()
		val failure = TrustStoreError.operationFailed(details = "index unreadable")
		coEvery { trustStore.scopes() } returns failure.left()

		useCase.upsert(profile("work")).shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("upsert of a stored profile leaves the trust store alone") {
		coEvery { configRepository.loadConfig() } returns baseConfig.copy(profiles = mapOf("work" to profile("work"))).right()
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()

		useCase.upsert(profile("work", hash = HashAlgorithm.SHA512)).shouldBeRight()

		coVerify(exactly = 0) { trustStore.scopes() }
		coVerify(exactly = 0) { trustStore.clearProfileScope(any()) }
	}

	test("a read-only trust store is left alone when a profile is created or removed") {
		every { trustStore.readOnly } returns true
		coEvery { configRepository.loadConfig() } returns baseConfig.copy(profiles = mapOf("home" to profile("home"))).right()
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()

		useCase.upsert(profile("work")).shouldBeRight()
		useCase.remove("home").shouldBeRight()

		coVerify(exactly = 0) { trustStore.scopes() }
		coVerify(exactly = 0) { trustStore.clearProfileScope(any()) }
	}

	test("a profile created under the name of a removed one trusts none of the certificates the removed one trusted, however it was removed") {
		val store = FileTrustStore(tempdir().toPath())
		val repository = FileConfigRepository(tempdir().toPath().resolve("config.json"))
		val profiles = ManageProfileUseCase(repository, store)
		val work = TrustScope.Profile("work")
		store.add(TrustScope.Global, selfSignedDer("Global CA"), TrustedCertificateType.CA).shouldBeRight()

		profiles.upsert(profile("work")).shouldBeRight()
		store.add(work, selfSignedDer("Contractor CA"), TrustedCertificateType.CA).shouldBeRight()
		profiles.remove("work").shouldBeRight()
		store.list(work).shouldBeRight().shouldBeEmpty()

		profiles.upsert(profile("work")).shouldBeRight()
		store.add(work, selfSignedDer("Test CA"), TrustedCertificateType.CA).shouldBeRight()
		repository.saveConfig(repository.getCurrentConfig().copy(profiles = emptyMap())).shouldBeRight()
		profiles.upsert(profile("work")).shouldBeRight()

		store.list(work).shouldBeRight().shouldBeEmpty()
		store.resolve(work).shouldBeRight().shouldHaveSize(1)
	}

	test("upsert drops no trust scope and saves nothing when the stored configuration cannot be read") {
		val failure = ConfigurationError.loadFailed(details = "config.json is not valid JSON")
		coEvery { configRepository.loadConfig() } returns failure.left()
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.upsert(profile("work")).shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { trustStore.scopes() }
		coVerify(exactly = 0) { trustStore.clearProfileScope(any()) }
		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
	}

	test("get and remove return the read's error when the stored configuration cannot be read, and remove saves and drops nothing") {
		val failure = ConfigurationError.loadFailed(details = "config.json is not valid JSON")
		coEvery { configRepository.loadConfig() } returns failure.left()
		coEvery { configRepository.getCurrentConfig() } returns baseConfig.copy(profiles = mapOf("work" to profile("work")))
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()
		coEvery { trustStore.clearProfileScope(any()) } returns Unit.right()

		useCase.get("work").shouldBeLeft() shouldBe failure
		useCase.remove("work").shouldBeLeft() shouldBe failure

		coVerify(exactly = 0) { configRepository.saveConfig(any()) }
		coVerify(exactly = 0) { trustStore.scopes() }
		coVerify(exactly = 0) { trustStore.clearProfileScope(any()) }
	}

	test("a profile created while the stored configuration cannot be read leaves every stored profile's certificates in place, and the error names the file") {
		val store = FileTrustStore(tempdir().toPath())
		val configFile = tempdir().toPath().resolve("config.json")
		val work = TrustScope.Profile("work")
		ManageProfileUseCase(FileConfigRepository(configFile), store).upsert(profile("work")).shouldBeRight()
		store.add(work, selfSignedDer("Contractor CA"), TrustedCertificateType.CA).shouldBeRight()
		val stored = configFile.readText()
		configFile.writeText("{ not json")

		ManageProfileUseCase(FileConfigRepository(configFile), store).upsert(profile("other"))
			.shouldBeLeft().shouldBeInstanceOf<ConfigurationError.LoadFailed>()
			.text shouldBe LocalizableText.of(MessageKey.CONFIG_UNREADABLE, configFile.toAbsolutePath().toString())

		configFile.writeText(stored)
		store.list(work).shouldBeRight().shouldHaveSize(1)
	}

	test("a profile stored under a key other than its name keeps the scopes of both when a profile is created or removed") {
		val config = baseConfig.copy(profiles = mapOf("work" to profile("Work"), "home" to profile("home")))
		coEvery { configRepository.loadConfig() } returns config.right()
		coEvery { configRepository.saveConfig(any()) } returns Unit.right()
		coEvery { trustStore.scopes() } returns setOf(
			TrustScope.Profile("work"),
			TrustScope.Profile("Work"),
			TrustScope.Profile("home"),
			TrustScope.Profile("gone"),
		).right()
		coEvery { trustStore.clearProfileScope(any()) } returns Unit.right()

		useCase.upsert(profile("new")).shouldBeRight()
		useCase.remove("home").shouldBeRight()

		coVerify { trustStore.clearProfileScope("gone") }
		coVerify { trustStore.clearProfileScope("home") }
		coVerify(exactly = 0) { trustStore.clearProfileScope("work") }
		coVerify(exactly = 0) { trustStore.clearProfileScope("Work") }
	}
})


