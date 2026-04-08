package cz.pizavo.omnisign.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Verifies [ServerConfigLoader] YAML parsing and fallback behavior.
 */
class ServerConfigLoaderTest : FunSpec({

	val loader = ServerConfigLoader()

	test("load returns classpath defaults when explicit path does not exist") {
		val config = loader.load("/nonexistent/path.yml")
		config.host shouldBe "0.0.0.0"
		config.port shouldBe 50080
		config.tlsPort shouldBe 50443
		config.development.shouldBeTrue()
		config.proxyMode.shouldBeFalse()
		config.requireLogin.shouldBeFalse()
		config.tls.shouldBeNull()
		config.cors.shouldBeNull()
		config.allowedOperations shouldContainExactlyInAnyOrder
				setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)
		config.allowedCertificateAliases.shouldBeNull()
	}

	test("load parses a YAML file with all fields") {
		val yaml = """
			host: "127.0.0.1"
			port: 9090
			tlsPort: 9443
			development: true
			proxyMode: true
			requireLogin: true
			tls:
			  keystorePath: "/tmp/ks.p12"
			  keystorePassword: "secret"
			  keyAlias: "mykey"
			  privateKeyPassword: "privpw"
			cors:
			  allowedOrigins:
			    - "https://example.com"
			  allowCredentials: true
			maxFileSize: 52428800
		""".trimIndent()

		val tmpFile = File.createTempFile("server-test-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.host shouldBe "127.0.0.1"
		config.port shouldBe 9090
		config.tlsPort shouldBe 9443
		config.development.shouldBeTrue()
		config.proxyMode.shouldBeTrue()
		config.requireLogin.shouldBeTrue()

		config.tls.shouldNotBeNull()
		config.tls.keystorePath shouldBe "/tmp/ks.p12"
		config.tls.keystorePassword shouldBe "secret"
		config.tls.keyAlias shouldBe "mykey"
		config.tls.privateKeyPassword shouldBe "privpw"

		config.cors.shouldNotBeNull()
		config.cors.allowedOrigins shouldBe listOf("https://example.com")
		config.cors.allowCredentials.shouldBeTrue()

		config.maxFileSize shouldBe 52428800L
	}

	test("load ignores unknown YAML properties") {
		val yaml = """
			host: "localhost"
			unknownField: "ignored"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-unknown-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.host shouldBe "localhost"
	}

	test("load falls back to classpath resource when no explicit path") {
		val config = loader.load()
		config.host shouldBe "0.0.0.0"
		config.port shouldBe 50080
		config.development.shouldBeTrue()
		config.allowedOperations shouldContainExactlyInAnyOrder
				setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)
		config.allowedCertificateAliases.shouldBeNull()
	}

	test("load parses allowedOperations including SIGN") {
		val yaml = """
			allowedOperations:
			  - SIGN
			  - VALIDATE
			  - TIMESTAMP
			allowedCertificateAliases:
			  - "university-seal"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-ops-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.allowedOperations shouldContainExactlyInAnyOrder
				setOf(AllowedOperation.SIGN, AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)
		config.allowedCertificateAliases.shouldNotBeNull()
		config.allowedCertificateAliases shouldBe listOf("university-seal")
	}
})


