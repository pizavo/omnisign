package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileFacts
import cz.pizavo.omnisign.data.secret.SecretFileReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempFile
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * Verifies that [ServerConfigLoader] applies value-only environment substitution: `${NAME}`
 * in a YAML value is expanded, while a placeholder in a comment is left untouched (so a
 * commented reference to an unset variable cannot abort startup).
 */
class ServerConfigLoaderEnvSubstitutionTest : FunSpec({

	val directory = tempdir().toPath()

	test("loadFromString expands an environment variable in a value") {
		val ref = "\${OMNISIGN_TEST_HOST}"
		val loader = ServerConfigLoader { if (it == "OMNISIGN_TEST_HOST") "10.0.0.5" else null }
		val config = loader.loadFromString("""
listen:
  host: "$ref"
""")
		config.listen.host shouldBe "10.0.0.5"
	}

	test("loadFromString does not expand an environment variable in a comment") {
		val ref = "\${OMNISIGN_UNSET}"
		val loader = ServerConfigLoader { null }
		val config = loader.loadFromString("""
# host: "$ref"
development: false
""")
		config.development shouldBe false
	}

	test("loadFromString fails fast when a value references an unset variable") {
		val ref = "\${OMNISIGN_UNSET}"
		val loader = ServerConfigLoader { null }
		val ex = shouldThrow<IllegalStateException> {
			loader.loadFromString("""
listen:
  host: "$ref"
""")
		}
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "OMNISIGN_UNSET"
	}

	test("loadFromString expands an OMNISIGN_ placeholder from the file its variable with _FILE names") {
		val ref = "\${OMNISIGN_OAUTH_NONCE_SECRET}"
		val nonceFile = createTempFile(directory, "nonce").also { it.writeText("nonce-secret\n") }
		val acceptingReader = EnvironmentSecretReader(
			SecretFileReader(
				probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
				effectiveUid = { 1000 },
			),
			osName = "Linux",
		)
		val environment = mapOf("OMNISIGN_OAUTH_NONCE_SECRET_FILE" to nonceFile.toString())
		val loader = ServerConfigLoader(placeholderResolver(acceptingReader, environment::get))
		val config = loader.loadFromString("""
auth:
  oauthStateSecret: "$ref"
""")
		config.auth?.oauthStateSecret?.value shouldBe "nonce-secret"
	}
})
