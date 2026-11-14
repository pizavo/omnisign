package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Verifies [MigrateTrustedCertificatesUseCase] reads legacy inline trusted certificates straight
 * from the on-disk config, imports them into the [FileTrustStore] under the correct scope, clears
 * the inline lists, and is a no-op when there is nothing to migrate. A config the typed model rejects
 * is never replaced by the cleared defaults, and one that starts with a byte order mark is migrated.
 * A store that cannot take the certificates, as one whose index cannot be read, stops the migration
 * with the config file left as it was, while a certificate the store rejects as not a certificate
 * is skipped.
 */
class MigrateTrustedCertificatesUseCaseTest : FunSpec({

	fun selfSignedDerBase64(cn: String): String {
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
		val der = JcaX509CertificateConverter().getCertificate(builder.build(signer)).encoded
		return Base64.getEncoder().encodeToString(der)
	}

	test("migrates inline certs from the config file into the store and clears them") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val caB64 = selfSignedDerBase64("Root")
		val tsaB64 = selfSignedDerBase64("Tsa")
		configPath.writeText(
			"""
			{
			  "global": {
			    "validation": {
			      "trustedCertificates": [
			        { "name": "ca", "type": "CA", "certificateBase64": "$caB64", "subjectDN": "CN=Root" }
			      ]
			    }
			  },
			  "profiles": {
			    "p1": {
			      "name": "p1",
			      "validation": {
			        "trustedCertificates": [
			          { "name": "tsa", "type": "TSA", "certificateBase64": "$tsaB64", "subjectDN": "CN=Tsa" }
			        ]
			      }
			    }
			  }
			}
			""".trimIndent(),
		)
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeRight() shouldBe 2

		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.CA
		store.list(TrustScope.Profile("p1")).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
		repo.getCurrentConfig().global.validation.trustedCertificates.shouldBeEmpty()
		repo.getCurrentConfig().profiles.getValue("p1").validation!!.trustedCertificates.shouldBeEmpty()
	}

	test("a store whose index cannot be read stops the migration and leaves the inline certs in the config file") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val text = """
			{
			  "global": {
			    "validation": {
			      "trustedCertificates": [
			        { "name": "ca", "type": "CA", "certificateBase64": "${selfSignedDerBase64("Root")}", "subjectDN": "CN=Root" }
			      ]
			    }
			  }
			}
			""".trimIndent()
		configPath.writeText(text)
		val index = dir.resolve("trusted-certs").createDirectories().resolve("contents.cbor")
		index.writeBytes(byteArrayOf(0xA1.toByte()))

		val error = MigrateTrustedCertificatesUseCase(
			FileConfigRepository(configPath),
			FileTrustStore(index.parent),
			configPath,
		)().shouldBeLeft()

		error.shouldBeInstanceOf<TrustStoreError.StorageFailed>().text shouldBe
			LocalizableText.of(MessageKey.TRUSTSTORE_INDEX_UNREADABLE, index.toAbsolutePath().toString())
		configPath.readText() shouldBe text
		index.readBytes().toList() shouldBe listOf(0xA1.toByte())
	}

	test("an inline cert the store rejects as not a certificate is skipped, and the others are migrated and cleared") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val notACertificate = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
		configPath.writeText(
			"""
			{
			  "global": {
			    "validation": {
			      "trustedCertificates": [
			        { "name": "broken", "type": "CA", "certificateBase64": "$notACertificate", "subjectDN": "CN=Broken" },
			        { "name": "ca", "type": "CA", "certificateBase64": "${selfSignedDerBase64("Root")}", "subjectDN": "CN=Root" }
			      ]
			    }
			  }
			}
			""".trimIndent(),
		)
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeRight() shouldBe 1

		store.list(TrustScope.Global).shouldBeRight().single().subjectDN shouldBe "O=OmniSign Test,CN=Root"
		repo.getCurrentConfig().global.validation.trustedCertificates.shouldBeEmpty()
	}

	test("inline certs in a config file that starts with a byte order mark are migrated") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val caB64 = selfSignedDerBase64("Root")
		configPath.writeText(
			"\uFEFF" + """{ "global": { "validation": { "trustedCertificates": [ { "name": "ca", "type": "CA", "certificateBase64": "$caB64" } ] } } }""",
		)
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeRight() shouldBe 1

		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.CA
		repo.getCurrentConfig().global.validation.trustedCertificates.shouldBeEmpty()
	}

	test("a missing config file is a no-op") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeRight() shouldBe 0
		store.list(TrustScope.Global).shouldBeRight().shouldBeEmpty()
	}

	test("a config without inline certs is a no-op") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		configPath.writeText("""{ "global": { "validation": { "trustedCertificates": [] } } }""")
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeRight() shouldBe 0
	}

	test("a config the typed model rejects is left as it was, though its certificates are migrated") {
		val dir = Files.createTempDirectory("migrate-test")
		val configPath = dir.resolve("config.json")
		val caB64 = selfSignedDerBase64("Root")
		val text = """
			{
			  "global": {
			    "defaultHashAlgorithm": "SHA3_1024",
			    "validation": {
			      "trustedCertificates": [
			        { "name": "ca", "type": "CA", "certificateBase64": "$caB64", "subjectDN": "CN=Root" }
			      ]
			    }
			  },
			  "profiles": { "work": { "name": "work" } }
			}
			""".trimIndent()
		configPath.writeText(text)
		val repo = FileConfigRepository(configPath)
		val store = FileTrustStore(dir.resolve("trusted-certs"))

		MigrateTrustedCertificatesUseCase(repo, store, configPath)().shouldBeLeft()

		configPath.readText() shouldBe text
		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.CA
	}
})
