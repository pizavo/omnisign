package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.*
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
 * Verifies [ManageTrustedListsUseCase] TL source CRUD, draft CRUD,
 * TSP management, service management, and profile-scoped operations.
 */
class ManageTrustedListsUseCaseTest : FunSpec({

	val configRepository: ConfigRepository = mockk()
	val useCase = ManageTrustedListsUseCase(configRepository)

	val baseConfig = AppConfig()

	fun tl(name: String) = CustomTrustedListConfig(name = name, source = "https://example.com/$name.xml")

	fun draft(name: String, tsps: List<TrustServiceProviderDraft> = emptyList()) =
		CustomTrustedListDraft(name = name, trustServiceProviders = tsps)

	fun tsp(name: String, services: List<TrustServiceDraft> = emptyList()) =
		TrustServiceProviderDraft(name = name, services = services)

	fun service(name: String) = TrustServiceDraft(
		name = name,
		typeIdentifier = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
		status = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
		certificatePath = "/certs/$name.pem"
	)

	test("addTrustedList stores a global TL source") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.addTrustedList(tl("my-tl")).shouldBeRight()

		saved.captured.global.validation.customTrustedLists.shouldHaveSize(1)
		saved.captured.global.validation.customTrustedLists.first().name shouldBe "my-tl"
	}

	test("addTrustedList replaces existing entry with same name") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					customTrustedLists = listOf(tl("my-tl"))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		val updated = CustomTrustedListConfig(name = "my-tl", source = "https://new-url.com/tl.xml")
		useCase.addTrustedList(updated).shouldBeRight()

		saved.captured.global.validation.customTrustedLists.shouldHaveSize(1)
		saved.captured.global.validation.customTrustedLists.first().source shouldBe "https://new-url.com/tl.xml"
	}

	test("addTrustedList stores a profile-scoped TL source") {
		val config = baseConfig.copy(
			profiles = mapOf("p1" to ProfileConfig(name = "p1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.addTrustedList(tl("profile-tl"), profileName = "p1").shouldBeRight()

		saved.captured.profiles["p1"]?.validation?.customTrustedLists?.shouldHaveSize(1)
	}

	test("addTrustedList returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.addTrustedList(tl("tl"), profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "ghost"
	}

	test("removeTrustedList removes a global TL source") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					customTrustedLists = listOf(tl("a"), tl("b"))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.removeTrustedList("a").shouldBeRight()

		saved.captured.global.validation.customTrustedLists.shouldHaveSize(1)
		saved.captured.global.validation.customTrustedLists.first().name shouldBe "b"
	}

	test("removeTrustedList returns error for unknown global TL name") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.removeTrustedList("nonexistent")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("removeTrustedList returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.removeTrustedList("tl", profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("removeTrustedList returns error for unknown TL name in profile") {
		val config = baseConfig.copy(
			profiles = mapOf("p1" to ProfileConfig(name = "p1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config

		useCase.removeTrustedList("nonexistent", profileName = "p1")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("listTrustedLists returns global list") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					customTrustedLists = listOf(tl("a"), tl("b"))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		val result = useCase.listTrustedLists().shouldBeRight()
		result.shouldHaveSize(2)
	}

	test("listTrustedLists returns empty list when none configured") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.listTrustedLists().shouldBeRight().shouldBeEmpty()
	}

	test("listTrustedLists returns profile-scoped list") {
		val config = baseConfig.copy(
			profiles = mapOf(
				"p1" to ProfileConfig(
					name = "p1",
					validation = ValidationConfig(customTrustedLists = listOf(tl("profile-tl")))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns config

		useCase.listTrustedLists(profileName = "p1").shouldBeRight().shouldHaveSize(1)
	}

	test("listTrustedLists returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.listTrustedLists(profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("upsertDraft stores a new draft") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsertDraft(draft("d1")).shouldBeRight()

		saved.captured.tlDrafts.shouldContainKey("d1")
	}

	test("upsertDraft replaces existing draft") {
		val existing = baseConfig.copy(tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("old")))))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsertDraft(draft("d1", listOf(tsp("new")))).shouldBeRight()

		saved.captured.tlDrafts["d1"]?.trustServiceProviders?.first()?.name shouldBe "new"
	}

	test("getDraft returns existing draft") {
		val existing = baseConfig.copy(tlDrafts = mapOf("d1" to draft("d1")))
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.getDraft("d1").shouldBeRight().name shouldBe "d1"
	}

	test("getDraft returns error for unknown draft") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.getDraft("missing")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("listDrafts returns all drafts") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("a" to draft("a"), "b" to draft("b"))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.listDrafts().shouldBeRight().shouldHaveSize(2)
	}

	test("listDrafts returns empty map when none stored") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.listDrafts().shouldBeRight().shouldBeEmpty()
	}

	test("deleteDraft removes existing draft") {
		val existing = baseConfig.copy(tlDrafts = mapOf("d1" to draft("d1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.deleteDraft("d1").shouldBeRight()
		saved.captured.tlDrafts.shouldBeEmpty()
	}

	test("deleteDraft returns error for unknown draft") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.deleteDraft("ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("upsertTsp adds a TSP to an existing draft") {
		val existing = baseConfig.copy(tlDrafts = mapOf("d1" to draft("d1")))
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsertTsp("d1", tsp("tsp-a")).shouldBeRight()

		saved.captured.tlDrafts["d1"]?.trustServiceProviders?.shouldHaveSize(1)
	}

	test("upsertTsp replaces TSP with same name") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("tsp-a", listOf(service("s1"))))))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsertTsp("d1", tsp("tsp-a")).shouldBeRight()

		saved.captured.tlDrafts["d1"]?.trustServiceProviders?.first()?.services?.shouldBeEmpty()
	}

	test("upsertTsp returns error for unknown draft") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig

		useCase.upsertTsp("unknown", tsp("t"))
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("removeTsp removes TSP from draft") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("tsp-a"), tsp("tsp-b"))))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.removeTsp("d1", "tsp-a").shouldBeRight()

		saved.captured.tlDrafts["d1"]?.trustServiceProviders?.shouldHaveSize(1)
		saved.captured.tlDrafts["d1"]?.trustServiceProviders?.first()?.name shouldBe "tsp-b"
	}

	test("removeTsp returns error for unknown TSP name") {
		val existing = baseConfig.copy(tlDrafts = mapOf("d1" to draft("d1")))
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.removeTsp("d1", "nonexistent")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("upsertService adds a service under a TSP") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("tsp-a"))))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.upsertService("d1", "tsp-a", service("svc-1")).shouldBeRight()

		saved.captured.tlDrafts["d1"]
			?.trustServiceProviders?.first()
			?.services?.shouldHaveSize(1)
	}

	test("upsertService replaces service with same name") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("tsp-a", listOf(service("svc-1"))))))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		val updatedService = TrustServiceDraft(
			name = "svc-1",
			typeIdentifier = "http://other",
			status = "http://status",
			certificatePath = "/new.pem"
		)
		useCase.upsertService("d1", "tsp-a", updatedService).shouldBeRight()

		saved.captured.tlDrafts["d1"]
			?.trustServiceProviders?.first()
			?.services?.first()?.certificatePath shouldBe "/new.pem"
	}

	test("upsertService returns error for unknown TSP") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.upsertService("d1", "ghost-tsp", service("s"))
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "ghost-tsp"
	}

	test("removeService removes a service from a TSP") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf(
				"d1" to draft("d1", listOf(tsp("tsp-a", listOf(service("s1"), service("s2")))))
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

		useCase.removeService("d1", "tsp-a", "s1").shouldBeRight()

		val services = saved.captured.tlDrafts["d1"]
			?.trustServiceProviders?.first()?.services
		services?.shouldHaveSize(1)
		services?.first()?.name shouldBe "s2"
	}

	test("removeService returns error for unknown service name") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1", listOf(tsp("tsp-a"))))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.removeService("d1", "tsp-a", "ghost-svc")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}

	test("removeService returns error for unknown TSP") {
		val existing = baseConfig.copy(
			tlDrafts = mapOf("d1" to draft("d1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns existing

		useCase.removeService("d1", "ghost-tsp", "svc")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
	
	// ── Trusted certificate CRUD ──────────────────────────────────────────────
	
	fun cert(name: String, type: TrustedCertificateType = TrustedCertificateType.ANY) =
		TrustedCertificateConfig(name = name, type = type, certificateBase64 = "AAAA", subjectDN = "CN=$name")
	
	test("addTrustedCertificate stores a global cert") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.addTrustedCertificate(cert("my-ca")).shouldBeRight()
		
		saved.captured.global.validation.trustedCertificates.shouldHaveSize(1)
		saved.captured.global.validation.trustedCertificates.first().name shouldBe "my-ca"
	}
	
	test("addTrustedCertificate replaces existing entry with same name") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					trustedCertificates = listOf(cert("my-ca"))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		val updated = cert("my-ca", TrustedCertificateType.TSA)
		useCase.addTrustedCertificate(updated).shouldBeRight()
		
		saved.captured.global.validation.trustedCertificates.shouldHaveSize(1)
		saved.captured.global.validation.trustedCertificates.first().type shouldBe TrustedCertificateType.TSA
	}
	
	test("addTrustedCertificate to profile stores in that profile") {
		val config = baseConfig.copy(
			profiles = mapOf("p1" to ProfileConfig(name = "p1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.addTrustedCertificate(cert("profile-cert"), profileName = "p1").shouldBeRight()
		
		saved.captured.profiles["p1"]?.validation?.trustedCertificates?.shouldHaveSize(1)
	}
	
	test("addTrustedCertificate returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.addTrustedCertificate(cert("cert"), profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "ghost"
	}
	
	test("removeTrustedCertificate removes a global cert") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					trustedCertificates = listOf(cert("a"), cert("b", TrustedCertificateType.TSA))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.removeTrustedCertificate("a").shouldBeRight()
		
		saved.captured.global.validation.trustedCertificates.shouldHaveSize(1)
		saved.captured.global.validation.trustedCertificates.first().name shouldBe "b"
	}
	
	test("removeTrustedCertificate returns error when name not found") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.removeTrustedCertificate("nonexistent")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
	
	test("listTrustedCertificates returns global list") {
		val existing = baseConfig.copy(
			global = baseConfig.global.copy(
				validation = baseConfig.global.validation.copy(
					trustedCertificates = listOf(cert("a"), cert("b"))
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns existing
		
		val result = useCase.listTrustedCertificates().shouldBeRight()
		result.shouldHaveSize(2)
	}
	
	test("listTrustedCertificates returns empty list when none configured") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.listTrustedCertificates().shouldBeRight().shouldBeEmpty()
	}
	
	test("listTrustedCertificates returns profile-scoped list") {
		val config = baseConfig.copy(
			profiles = mapOf(
				"p1" to ProfileConfig(
					name = "p1",
					validation = ValidationConfig(
						trustedCertificates = listOf(cert("profile-cert"))
					)
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		
		useCase.listTrustedCertificates(profileName = "p1").shouldBeRight().shouldHaveSize(1)
	}
	
	test("listTrustedCertificates returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.listTrustedCertificates(profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
	
	test("removeTrustedCertificate removes a profile-scoped cert") {
		val config = baseConfig.copy(
			profiles = mapOf(
				"p1" to ProfileConfig(
					name = "p1",
					validation = ValidationConfig(
						trustedCertificates = listOf(cert("a"), cert("b"))
					)
				)
			)
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		val saved = slot<AppConfig>()
		coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
		
		useCase.removeTrustedCertificate("a", profileName = "p1").shouldBeRight()
		
		saved.captured.profiles["p1"]?.validation?.trustedCertificates?.shouldHaveSize(1)
		saved.captured.profiles["p1"]?.validation?.trustedCertificates?.first()?.name shouldBe "b"
	}
	
	test("removeTrustedCertificate returns error for unknown profile") {
		coEvery { configRepository.getCurrentConfig() } returns baseConfig
		
		useCase.removeTrustedCertificate("cert", profileName = "ghost")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
	
	test("removeTrustedCertificate returns error for unknown cert name in profile") {
		val config = baseConfig.copy(
			profiles = mapOf("p1" to ProfileConfig(name = "p1"))
		)
		coEvery { configRepository.getCurrentConfig() } returns config
		
		useCase.removeTrustedCertificate("nonexistent", profileName = "p1")
			.shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
})

