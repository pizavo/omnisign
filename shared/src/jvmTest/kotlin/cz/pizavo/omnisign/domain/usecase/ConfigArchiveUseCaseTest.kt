package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.data.serializer.XmlConfigSerializer
import cz.pizavo.omnisign.data.serializer.YamlConfigSerializer
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Date

/**
 * Verifies [ConfigArchiveUseCase] round-trips the configuration and its scoped trust material
 * through the ZIP archive: a global cert, a profile-scoped cert (with name override), and a
 * cert-free export.
 */
class ConfigArchiveUseCaseTest : FunSpec({

	fun serializers() = ConfigSerializerRegistry(
		listOf(JsonConfigSerializer(), XmlConfigSerializer(), YamlConfigSerializer()),
	)

	fun inMemoryRepo(initial: AppConfig): ConfigRepository = object : ConfigRepository {
		private var current = initial
		override suspend fun loadConfig(): OperationResult<AppConfig> = current.right()
		override suspend fun saveConfig(config: AppConfig): OperationResult<Unit> {
			current = config
			return Unit.right()
		}

		override suspend fun getCurrentConfig(): AppConfig = current
	}

	fun newStore() = FileTrustStore(Files.createTempDirectory("archive-test"))

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

	fun archiveUseCase(repo: ConfigRepository, store: FileTrustStore) =
		ConfigArchiveUseCase(repo, ExportImportConfigUseCase(repo, serializers()), store)

	test("global archive round-trips the config and a global trusted cert") {
		val sourceStore = newStore()
		val fingerprint = sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		val archive = archiveUseCase(inMemoryRepo(AppConfig(global = GlobalConfig())), sourceStore)
			.exportGlobal(ConfigFormat.JSON).shouldBeRight()

		ConfigArchiveUseCase.isArchive(archive) shouldBe true

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importGlobal(archive).shouldBeRight()

		val restored = destStore.list(TrustScope.Global).shouldBeRight()
		restored.map { it.fingerprint } shouldBe listOf(fingerprint)
		restored.single().type shouldBe TrustedCertificateType.CA
	}

	test("app archive round-trips a profile and its profile-scoped trusted cert") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA).shouldBeRight()
		val archive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.YAML).shouldBeRight()

		val destRepo = inMemoryRepo(AppConfig())
		val destStore = newStore()
		archiveUseCase(destRepo, destStore).importApp(archive).shouldBeRight()

		destRepo.getCurrentConfig().profiles.shouldContainKey("p1")
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
	}

	test("profile archive import honors the name override and scopes trust to the new name") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("orig" to ProfileConfig(name = "orig"))))
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Profile("orig"), selfSignedDer("X"), TrustedCertificateType.ANY).shouldBeRight()
		val archive = archiveUseCase(sourceRepo, sourceStore).exportProfile("orig", ConfigFormat.JSON).shouldBeRight()

		val destRepo = inMemoryRepo(AppConfig())
		val destStore = newStore()
		archiveUseCase(destRepo, destStore).importProfile(archive, overrideName = "renamed").shouldBeRight() shouldBe "renamed"

		destRepo.getCurrentConfig().profiles.shouldContainKey("renamed")
		destStore.list(TrustScope.Profile("renamed")).shouldBeRight().single().type shouldBe TrustedCertificateType.ANY
		destStore.list(TrustScope.Profile("orig")).shouldBeRight().shouldBeEmpty()
	}

	test("a cert-free export still produces a valid archive that imports cleanly") {
		val archive = archiveUseCase(inMemoryRepo(AppConfig()), newStore())
			.exportGlobal(ConfigFormat.JSON).shouldBeRight()

		ConfigArchiveUseCase.isArchive(archive) shouldBe true

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importGlobal(archive).shouldBeRight()
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
	}

	test("two exports of identical state are byte-for-byte identical") {
		val store = newStore()
		store.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		val repo = inMemoryRepo(AppConfig(global = GlobalConfig()))
		val first = archiveUseCase(repo, store).exportGlobal(ConfigFormat.JSON).shouldBeRight()
		val second = archiveUseCase(repo, store).exportGlobal(ConfigFormat.JSON).shouldBeRight()
		first shouldBe second
	}
})
