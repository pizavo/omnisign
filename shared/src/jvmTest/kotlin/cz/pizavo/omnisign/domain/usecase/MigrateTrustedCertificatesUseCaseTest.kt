package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies [MigrateTrustedCertificatesUseCase] reads legacy inline trusted certificates straight
 * from the on-disk config, imports them into the [FileTrustStore] under the correct scope, clears
 * the inline lists, and is a no-op when there is nothing to migrate. A config the typed model rejects
 * is never replaced by the cleared defaults, and one that starts with a byte order mark is migrated.
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
