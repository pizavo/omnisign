package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
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
import kotlin.io.path.writeText

/**
 * Verifies [MigrateTrustedCertificatesUseCase] reads legacy inline trusted certificates straight
 * from the on-disk config, imports them into the [FileTrustStore] under the correct scope, clears
 * the inline lists, and is a no-op when there is nothing to migrate.
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
})
