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
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Date
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Verifies [ConfigArchiveUseCase] round-trips the configuration and its scoped trust material
 * through the ZIP archive: a global cert, in a global and in a full archive, a profile-scoped cert
 * (with name override), and a cert-free export. An archive of another kind than the import reads is refused with nothing
 * changed, and a global import restores the global trust scope alone. An archive whose manifest,
 * or a certificate it lists, cannot be read is refused in the same way, as are one whose entry
 * names are not UTF-8 and one that unpacks to more than an import accepts. An export holds the
 * same bytes in every time zone.
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

		override suspend fun setActiveProfile(name: String?): OperationResult<Unit> {
			current = current.copy(activeProfile = name)
			return Unit.right()
		}
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

	fun unzip(archive: ByteArray): Map<String, ByteArray> {
		val entries = LinkedHashMap<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
			generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
		}
		return entries
	}

	fun zip(entries: Map<String, ByteArray>): ByteArray {
		val buffer = ByteArrayOutputStream()
		ZipOutputStream(buffer).use { zip ->
			for ((name, bytes) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return buffer.toByteArray()
	}

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

	test("app archive restores the global settings' trusted cert beside a profile's") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		val globalFingerprint = sourceStore.add(TrustScope.Global, selfSignedDer("GlobalRoot"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		val profileFingerprint = sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileTsa"), TrustedCertificateType.TSA)
			.shouldBeRight().fingerprint
		val archive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.JSON).shouldBeRight()

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importApp(archive).shouldBeRight()

		val global = destStore.list(TrustScope.Global).shouldBeRight()
		global.map { it.fingerprint } shouldBe listOf(globalFingerprint)
		global.single().type shouldBe TrustedCertificateType.CA
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().map { it.fingerprint } shouldBe listOf(profileFingerprint)
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

	test("a profile kept under another key than its name exports its certificates under its name, so that its import restores them") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("key" to ProfileConfig(name = "other-name"))))
		val sourceStore = newStore()
		val fingerprint = sourceStore.add(TrustScope.Profile("key"), selfSignedDer("KeyedCa"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		val archive = archiveUseCase(sourceRepo, sourceStore).exportProfile("key", ConfigFormat.JSON).shouldBeRight()

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importProfile(archive).shouldBeRight() shouldBe "other-name"

		destStore.list(TrustScope.Profile("other-name")).shouldBeRight().map { it.fingerprint } shouldBe listOf(fingerprint)
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

	test("importApp refuses a global archive and leaves the configuration and the trust store as they were") {
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		val archive = archiveUseCase(inMemoryRepo(AppConfig()), sourceStore).exportGlobal(ConfigFormat.JSON).shouldBeRight()
		val existing = AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1")), activeProfile = "p1")
		val destRepo = inMemoryRepo(existing)
		val destStore = newStore()

		val error = archiveUseCase(destRepo, destStore).importApp(archive).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_DOCUMENT_GLOBAL_NOT_APP)
		destRepo.getCurrentConfig() shouldBe existing
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
	}

	test("importGlobal refuses a full archive and leaves the configuration and the trust store as they were") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA).shouldBeRight()
		val archive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.YAML).shouldBeRight()
		val existing = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))
		val destRepo = inMemoryRepo(existing)
		val destStore = newStore()

		val error = archiveUseCase(destRepo, destStore).importGlobal(archive).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_DOCUMENT_APP_NOT_GLOBAL)
		destRepo.getCurrentConfig() shouldBe existing
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
	}

	test("importProfile refuses a full archive and adds no profile") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA).shouldBeRight()
		val archive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.XML).shouldBeRight()
		val destRepo = inMemoryRepo(AppConfig())
		val destStore = newStore()

		val error = archiveUseCase(destRepo, destStore).importProfile(archive).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_DOCUMENT_APP_NOT_PROFILE)
		destRepo.getCurrentConfig() shouldBe AppConfig()
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
	}

	test("importGlobal restores only the manifest's global entries, even when it lists a profile's") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		val globalFingerprint = sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA).shouldBeRight()
		val fullArchive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.JSON).shouldBeRight()
		val globalText = ExportImportConfigUseCase(sourceRepo, serializers()).exportGlobal(ConfigFormat.JSON).shouldBeRight()
		val archive = zip(unzip(fullArchive) + ("config.json" to globalText.encodeToByteArray()))

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importGlobal(archive).shouldBeRight()

		destStore.list(TrustScope.Global).shouldBeRight().map { it.fingerprint } shouldBe listOf(globalFingerprint)
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
	}

	test("importApp refuses an archive whose manifest lists a certificate it does not hold, and changes nothing") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		val fingerprint = sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA)
			.shouldBeRight().fingerprint
		val entries = unzip(archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.JSON).shouldBeRight())
		val archive = zip(entries - "trusted-certs/$fingerprint.der")
		val existing = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))
		val destRepo = inMemoryRepo(existing)
		val destStore = newStore()

		val error = archiveUseCase(destRepo, destStore).importApp(archive).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_MISSING_DER_ENTRY, fingerprint)
		destRepo.getCurrentConfig() shouldBe existing
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
	}

	test("importApp refuses an archive holding a certificate that does not parse, and changes nothing") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		val fingerprint = sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA)
			.shouldBeRight().fingerprint
		val entries = unzip(archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.JSON).shouldBeRight())
		val archive = zip(entries + ("trusted-certs/$fingerprint.der" to "not a certificate".encodeToByteArray()))
		val existing = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))
		val destRepo = inMemoryRepo(existing)
		val destStore = newStore()

		archiveUseCase(destRepo, destStore).importApp(archive).shouldBeLeft().shouldBeInstanceOf<TrustStoreError.ParseFailed>()

		destRepo.getCurrentConfig() shouldBe existing
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
		destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
	}

	test("importApp refuses an archive whose manifest cannot be read, and changes nothing") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val entries = unzip(archiveUseCase(sourceRepo, newStore()).exportApp(ConfigFormat.JSON).shouldBeRight())
		val archive = zip(entries + ("trust-manifest.json" to "{".encodeToByteArray()))
		val existing = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))
		val destRepo = inMemoryRepo(existing)

		val error = archiveUseCase(destRepo, newStore()).importApp(archive).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_ARCHIVE_CORRUPT_MANIFEST)
		destRepo.getCurrentConfig() shouldBe existing
	}

	test("importGlobal and importProfile refuse an archive whose trust material is broken, and change nothing") {
		val sourceRepo = inMemoryRepo(AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"))))
		val sourceStore = newStore()
		val globalFingerprint = sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		val profileFingerprint = sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA)
			.shouldBeRight().fingerprint
		val globalEntries = unzip(archiveUseCase(sourceRepo, sourceStore).exportGlobal(ConfigFormat.JSON).shouldBeRight())
		val profileEntries = unzip(archiveUseCase(sourceRepo, sourceStore).exportProfile("p1", ConfigFormat.JSON).shouldBeRight())
		val existing = AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))

		fun broken(entries: Map<String, ByteArray>, fingerprint: String) = mapOf(
			"a certificate it does not hold" to zip(entries - "trusted-certs/$fingerprint.der"),
			"a certificate that does not parse" to
				zip(entries + ("trusted-certs/$fingerprint.der" to "not a certificate".encodeToByteArray())),
			"a manifest that cannot be read" to zip(entries + ("trust-manifest.json" to "{".encodeToByteArray())),
		)

		for ((case, archive) in broken(globalEntries, globalFingerprint)) {
			withClue("importGlobal, $case") {
				val destRepo = inMemoryRepo(existing)
				val destStore = newStore()

				archiveUseCase(destRepo, destStore).importGlobal(archive).shouldBeLeft()

				destRepo.getCurrentConfig() shouldBe existing
				destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
			}
		}
		for ((case, archive) in broken(profileEntries, profileFingerprint)) {
			withClue("importProfile, $case") {
				val destRepo = inMemoryRepo(existing)
				val destStore = newStore()

				archiveUseCase(destRepo, destStore).importProfile(archive).shouldBeLeft()

				destRepo.getCurrentConfig() shouldBe existing
				destStore.list(TrustScope.Profile("p1")).shouldBeRight().shouldBeEmpty()
			}
		}
	}

	test("importProfile restores only the certificates of the profile its configuration holds, though the manifest lists the global settings' and another profile's") {
		val sourceRepo = inMemoryRepo(
			AppConfig(profiles = mapOf("p1" to ProfileConfig(name = "p1"), "p2" to ProfileConfig(name = "p2"))),
		)
		val sourceStore = newStore()
		sourceStore.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		val profileFingerprint = sourceStore.add(TrustScope.Profile("p1"), selfSignedDer("ProfileCa"), TrustedCertificateType.TSA)
			.shouldBeRight().fingerprint
		sourceStore.add(TrustScope.Profile("p2"), selfSignedDer("OtherCa"), TrustedCertificateType.CA).shouldBeRight()
		val fullArchive = archiveUseCase(sourceRepo, sourceStore).exportApp(ConfigFormat.JSON).shouldBeRight()
		val profileText = ExportImportConfigUseCase(sourceRepo, serializers()).exportProfile("p1", ConfigFormat.JSON)
			.shouldBeRight()
		val archive = zip(unzip(fullArchive) + ("config.json" to profileText.encodeToByteArray()))

		val destStore = newStore()
		archiveUseCase(inMemoryRepo(AppConfig()), destStore).importProfile(archive).shouldBeRight() shouldBe "p1"

		destStore.list(TrustScope.Profile("p1")).shouldBeRight().map { it.fingerprint } shouldBe listOf(profileFingerprint)
		destStore.list(TrustScope.Profile("p2")).shouldBeRight().shouldBeEmpty()
		destStore.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
	}

	test("an archive that unpacks to exactly 64 MiB is imported, and one a byte larger is refused") {
		val limit = 64 * 1024 * 1024
		val configText = ExportImportConfigUseCase(inMemoryRepo(AppConfig()), serializers()).exportApp(ConfigFormat.JSON)
			.shouldBeRight().encodeToByteArray()

		fun unpackingTo(size: Int) = zip(mapOf("config.json" to configText, "padding.bin" to ByteArray(size - configText.size)))

		archiveUseCase(inMemoryRepo(AppConfig()), newStore()).importApp(unpackingTo(limit)).shouldBeRight()
		val error = archiveUseCase(inMemoryRepo(AppConfig()), newStore()).importApp(unpackingTo(limit + 1)).shouldBeLeft()
		error.details shouldBe "The archive unpacks to more than 64 MiB"
	}

	test("an archive with an entry name that is not UTF-8 is refused as unreadable") {
		val buffer = ByteArrayOutputStream()
		ZipOutputStream(buffer, Charset.forName("windows-1250")).use { zip ->
			zip.putNextEntry(ZipEntry("příloha.pdf"))
			zip.write(byteArrayOf(1, 2, 3))
			zip.closeEntry()
		}
		val destRepo = inMemoryRepo(AppConfig())

		val error = archiveUseCase(destRepo, newStore()).importApp(buffer.toByteArray()).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_ARCHIVE_UNREADABLE)
		destRepo.getCurrentConfig() shouldBe AppConfig()
	}

	test("an archive that unpacks to more than 64 MiB in all is refused, though no entry alone does") {
		val buffer = ByteArrayOutputStream()
		val mebibyte = ByteArray(1024 * 1024)
		ZipOutputStream(buffer).use { zip ->
			for (name in listOf("a.bin", "b.bin")) {
				zip.putNextEntry(ZipEntry(name))
				repeat(40) { zip.write(mebibyte) }
				zip.closeEntry()
			}
		}
		val destRepo = inMemoryRepo(AppConfig())

		val error = archiveUseCase(destRepo, newStore()).importApp(buffer.toByteArray()).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>().text shouldBe
			LocalizableText.of(MessageKey.CONFIG_ARCHIVE_UNREADABLE)
		error.details shouldBe "The archive unpacks to more than 64 MiB"
		destRepo.getCurrentConfig() shouldBe AppConfig()
	}

	test("an export holds the same bytes whatever time zone the machine that writes it is in") {
		val store = newStore()
		store.add(TrustScope.Global, selfSignedDer("Root"), TrustedCertificateType.CA).shouldBeRight()
		val repo = inMemoryRepo(AppConfig(global = GlobalConfig()))
		val original = TimeZone.getDefault()
		val exports = try {
			listOf("UTC", "Europe/Prague", "America/New_York").map { zone ->
				TimeZone.setDefault(TimeZone.getTimeZone(zone))
				archiveUseCase(repo, store).exportGlobal(ConfigFormat.JSON).shouldBeRight()
			}
		} finally {
			TimeZone.setDefault(original)
		}

		exports[1] shouldBe exports[0]
		exports[2] shouldBe exports[0]
	}
})
