package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileFacts
import cz.pizavo.omnisign.data.secret.SecretFileReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import tools.jackson.databind.ObjectMapper
import kotlin.io.path.createTempFile
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * Unit tests for the shared environment-variable substitution helpers used by both the
 * server.yml and signing.yml loaders.
 *
 * The secret reader [placeholderResolver] is given accepts every file and acts as it does on
 * Linux, so a value kept in a file is read on every operating system; the rules for such a file
 * are tested with the reader itself.
 */
class EnvVarSubstitutionTest : FunSpec({

	val mapper = ObjectMapper()
	val directory = tempdir().toPath()
	val secretReader = EnvironmentSecretReader(
		SecretFileReader(
			probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
			effectiveUid = { 1000 },
		),
		osName = "Linux",
	)

	/** An environment that holds only [variables]. */
	fun environmentOf(vararg variables: Pair<String, String>): (String) -> String? = mapOf(*variables)::get

	/** Write [content] to a new file and return its path. */
	fun secretFile(content: String): String =
		createTempFile(directory, "secret").also { it.writeText(content) }.toString()

	test("substituteEnvVars replaces a placeholder with the resolved value") {
		substituteEnvVars("a-\${X}-b") { if (it == "X") "v" else null } shouldBe "a-v-b"
	}

	test("substituteEnvVars leaves text without placeholders unchanged") {
		substituteEnvVars("plain text, no vars") { null } shouldBe "plain text, no vars"
	}

	test("substituteEnvVars ignores lowercase names (not a valid placeholder)") {
		substituteEnvVars("\${lower}") { "RESOLVED" } shouldBe "\${lower}"
	}

	test("substituteEnvVars fails fast on an unset variable") {
		val ex = shouldThrow<IllegalStateException> { substituteEnvVars("\${MISSING}") { null } }
		ex.message!! shouldContain "MISSING"
	}

	test("substituteTreeValues expands string values but never keys") {
		val root = mapper.createObjectNode()
		root.put("\${KEY}", "\${VAL}")
		substituteTreeValues(root) { if (it == "VAL") "resolved" else null }
		root.has("\${KEY}") shouldBe true
		root.get("\${KEY}").asString() shouldBe "resolved"
	}

	test("substituteTreeValues recurses into nested objects and arrays, leaving non-strings") {
		val root = mapper.createObjectNode()
		root.putObject("obj").put("c", "\${A}")
		root.putArray("arr").add("\${B}").add("plain")
		root.put("n", 5)
		substituteTreeValues(root) { mapOf("A" to "av", "B" to "bv")[it] }
		root.get("obj").get("c").asString() shouldBe "av"
		root.get("arr").get(0).asString() shouldBe "bv"
		root.get("arr").get(1).asString() shouldBe "plain"
		root.get("n").asInt() shouldBe 5
	}

	test("substituteTreeValues fails fast when a value references an unset variable") {
		val root = mapper.createObjectNode()
		root.put("a", "\${MISSING}")
		val ex = shouldThrow<IllegalStateException> { substituteTreeValues(root) { null } }
		ex.message!! shouldContain "MISSING"
	}

	test("substituteEnvVars names the file variable of an unset OMNISIGN_ name only") {
		val ownName = shouldThrow<IllegalStateException> { substituteEnvVars("\${OMNISIGN_MISSING}") { null } }
		val otherName = shouldThrow<IllegalStateException> { substituteEnvVars("\${MISSING}") { null } }

		ownName.message!! shouldContain "or set OMNISIGN_MISSING_FILE to the path of a file that holds the value"
		otherName.message!! shouldNotContain "_FILE"
	}

	test("placeholderResolver reads an OMNISIGN_ name from its variable") {
		placeholderResolver(secretReader, environmentOf("OMNISIGN_X" to "v"))("OMNISIGN_X") shouldBe "v"
	}

	test("placeholderResolver reads an OMNISIGN_ name from the file its variable with _FILE names") {
		val resolve = placeholderResolver(secretReader, environmentOf("OMNISIGN_X_FILE" to secretFile("from-file\n")))

		resolve("OMNISIGN_X") shouldBe "from-file"
	}

	test("placeholderResolver refuses an OMNISIGN_ name set both in its variable and in a file") {
		val resolve = placeholderResolver(
			secretReader,
			environmentOf("OMNISIGN_X" to "v", "OMNISIGN_X_FILE" to secretFile("other")),
		)

		shouldThrow<IllegalStateException> { resolve("OMNISIGN_X") }.message shouldBe
			"Both OMNISIGN_X and OMNISIGN_X_FILE are set. Set only one of them."
	}

	test("placeholderResolver still expands an OMNISIGN_ variable that is set but empty") {
		placeholderResolver(secretReader, environmentOf("OMNISIGN_X" to ""))("OMNISIGN_X") shouldBe ""
	}

	test("placeholderResolver returns null for an OMNISIGN_ name set nowhere") {
		placeholderResolver(secretReader, environmentOf())("OMNISIGN_X").shouldBeNull()
	}

	test("placeholderResolver reads any other name from its variable only, leaving a _FILE variable to its program") {
		val resolve = placeholderResolver(
			secretReader,
			environmentOf("SSL_CERT_FILE" to secretFile("not-a-value"), "OTHER" to "v"),
		)

		resolve("SSL_CERT").shouldBeNull()
		resolve("OTHER") shouldBe "v"
	}
})
