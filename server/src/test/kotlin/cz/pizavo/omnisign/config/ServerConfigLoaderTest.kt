package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Verifies [ServerConfigLoader] YAML parsing and the no-config fallback behavior.
 *
 * The loader does not consult the JAR's bundled `server.example.yml` as a fallback —
 * "no config found" deliberately means "use Kotlin defaults", and the validators in
 * `moduleWith` decide whether those defaults can start a server. These tests pin that
 * contract.
 */
class ServerConfigLoaderTest : FunSpec({

	val loader = ServerConfigLoader()

	test("load returns Kotlin defaults when explicit path does not exist (no classpath fallback)") {
		val config = loader.load("/nonexistent/path.yml")
		config.listen.host shouldBe "127.0.0.1"
		config.listen.port shouldBe 18080
		config.development.shouldBeFalse()
		config.proxy.shouldBeNull()
		config.tls.shouldBeNull()
		config.cors.shouldBeNull()
		config.auth.shouldBeNull()
		config.operations.allowed shouldContainExactlyInAnyOrder setOf(AllowedOperation.VALIDATE)
		config.operations.certificateAliases.shouldBeNull()
	}

	test("load parses a YAML file with all fields") {
		val yaml = """
			listen:
			  host: "127.0.0.1"
			  port: 9090
			development: true
			proxy:
			  enabled: true
			  trusted:
			    - "127.0.0.1"
			    - "::1"
			auth:
			  enabled: true
			tls:
			  port: 9443
			  keystorePath: "/tmp/ks.p12"
			  keyAlias: "mykey"
			cors:
			  allowedOrigins:
			    - "https://example.com"
			maxFileSize: 52428800
		""".trimIndent()

		val tmpFile = File.createTempFile("server-test-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.listen.host shouldBe "127.0.0.1"
		config.listen.port shouldBe 9090
		config.development.shouldBeTrue()
		config.proxy.shouldNotBeNull()
		config.proxy.enabled.shouldBeTrue()
		config.proxy.trusted shouldBe listOf("127.0.0.1", "::1")
		config.auth?.enabled.shouldBeTrue()

		config.tls.shouldNotBeNull()
		config.tls.port shouldBe 9443
		config.tls.keystorePath shouldBe "/tmp/ks.p12"
		config.tls.keyAlias shouldBe "mykey"

		config.cors.shouldNotBeNull()
		config.cors.allowedOrigins shouldBe listOf("https://example.com")

		config.maxFileSize shouldBe 52428800L
	}

	test("load rejects YAML that tries to set tls.keystorePassword (must use env var)") {
		val yaml = """
			tls:
			  keystorePath: "/tmp/ks.p12"
			  keystorePassword: "should-be-env-var"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-tls-pwd-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val ex = shouldThrow<Exception> { loader.load(tmpFile.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "OMNISIGN_TLS_KEYSTORE_PASSWORD"
	}

	test("load rejects YAML that tries to set auth.session.secret (must use env var)") {
		val yaml = """
			auth:
			  session:
			    secret: "should-be-env-var"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-session-secret-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val ex = shouldThrow<Exception> { loader.load(tmpFile.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "OMNISIGN_JWT_SECRET"
	}

	test("load rejects YAML that tries to set OIDC clientSecret (must use env var)") {
		val yaml = """
			auth:
			  providers:
			    - type: oidc
			      name: google
			      preset: GOOGLE
			      clientId: "id"
			      clientSecret: "should-be-env-var"
			      allowedEmailDomains: ["*"]
		""".trimIndent()

		val tmpFile = File.createTempFile("server-client-secret-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val ex = shouldThrow<Exception> { loader.load(tmpFile.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "OMNISIGN_OIDC_"
	}

	test("load fails fast on an unknown top-level YAML property") {
		val yaml = """
			development: false
			unknownField: "ignored"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-unknown-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val ex = shouldThrow<UnrecognizedPropertyException> {
			loader.load(tmpFile.absolutePath)
		}
		ex.propertyName shouldBe "unknownField"
	}

	test("load fails fast on a typo of a security-critical key (auth.enable vs auth.enabled)") {
		val yaml = """
			auth:
			  enable: true
		""".trimIndent()

		val tmpFile = File.createTempFile("server-auth-typo-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val ex = shouldThrow<UnrecognizedPropertyException> {
			loader.load(tmpFile.absolutePath)
		}
		ex.propertyName shouldBe "enable"
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "enabled"
	}

	test("load returns Kotlin defaults when called with no path and no CWD server.yml exists") {
		val config = loader.load()
		config.listen.host shouldBe "127.0.0.1"
		config.listen.port shouldBe 18080
		config.development.shouldBeFalse()
		config.cors.shouldBeNull()
		config.tls.shouldBeNull()
		config.proxy.shouldBeNull()
		config.auth.shouldBeNull()
		config.operations.allowed shouldContainExactlyInAnyOrder setOf(AllowedOperation.VALIDATE)
		config.operations.certificateAliases.shouldBeNull()
	}

	test("load parses operations.allowed including SIGN") {
		val yaml = """
			operations:
			  allowed:
			    - SIGN
			    - VALIDATE
			    - TIMESTAMP
			  certificateAliases:
			    - "university-seal"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-ops-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.operations.allowed shouldContainExactlyInAnyOrder
				setOf(AllowedOperation.SIGN, AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)
		config.operations.certificateAliases.shouldNotBeNull()
		config.operations.certificateAliases shouldBe listOf("university-seal")
	}

	test("load parses rateLimiting zone overrides") {
		val yaml = """
			rateLimiting:
			  auth:
			    limit: 5
			    refillPeriodSeconds: 30
			  api:
			    limit: 500
			    refillPeriodSeconds: 120
		""".trimIndent()

		val tmpFile = File.createTempFile("server-rl-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.rateLimiting.shouldNotBeNull()
		config.rateLimiting.auth.limit shouldBe 5
		config.rateLimiting.auth.refillPeriodSeconds shouldBe 30L
		config.rateLimiting.api.limit shouldBe 500
		config.rateLimiting.api.refillPeriodSeconds shouldBe 120L
	}

	test("rateLimiting is null when not specified") {
		val yaml = "development: false"

		val tmpFile = File.createTempFile("server-nrl-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.rateLimiting.shouldBeNull()
	}

	test("load parses hsts block nested under tls") {
		val yaml = """
			tls:
			  keystorePath: "/tmp/ks.p12"
			  hsts:
			    maxAgeSeconds: 600
			    includeSubDomains: false
			    preload: true
		""".trimIndent()

		val tmpFile = File.createTempFile("server-hsts-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.tls.shouldNotBeNull()
		config.tls.hsts.shouldNotBeNull()
		config.tls.hsts.maxAgeSeconds shouldBe 600L
		config.tls.hsts.includeSubDomains.shouldBeFalse()
		config.tls.hsts.preload.shouldBeTrue()
	}

	test("hsts defaults are applied when only the hsts block is present under tls") {
		val yaml = """
			tls:
			  keystorePath: "/tmp/ks.p12"
			  hsts: {}
		""".trimIndent()

		val tmpFile = File.createTempFile("server-hsts-defaults-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.tls.shouldNotBeNull()
		config.tls.hsts.shouldNotBeNull()
		config.tls.hsts.maxAgeSeconds shouldBe 31_536_000L
		config.tls.hsts.includeSubDomains.shouldBeTrue()
		config.tls.hsts.preload.shouldBeFalse()
	}

	test("hsts is null when tls block has no hsts entry") {
		val yaml = """
			tls:
			  keystorePath: "/tmp/ks.p12"
		""".trimIndent()

		val tmpFile = File.createTempFile("server-nhsts-", ".yml")
		tmpFile.deleteOnExit()
		tmpFile.writeText(yaml)

		val config = loader.load(tmpFile.absolutePath)
		config.tls.shouldNotBeNull()
		config.tls.hsts.shouldBeNull()
	}
})


