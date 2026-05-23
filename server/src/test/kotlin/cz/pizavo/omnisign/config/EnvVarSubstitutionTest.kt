package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for the shared environment-variable substitution helpers used by both the
 * server.yml and signing.yml loaders.
 */
class EnvVarSubstitutionTest : FunSpec({

	val mapper = ObjectMapper()

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
		root.get("\${KEY}").asText() shouldBe "resolved"
	}

	test("substituteTreeValues recurses into nested objects and arrays, leaving non-strings") {
		val root = mapper.createObjectNode()
		root.putObject("obj").put("c", "\${A}")
		root.putArray("arr").add("\${B}").add("plain")
		root.put("n", 5)
		substituteTreeValues(root) { mapOf("A" to "av", "B" to "bv")[it] }
		root.get("obj").get("c").asText() shouldBe "av"
		root.get("arr").get(0).asText() shouldBe "bv"
		root.get("arr").get(1).asText() shouldBe "plain"
		root.get("n").asInt() shouldBe 5
	}

	test("substituteTreeValues fails fast when a value references an unset variable") {
		val root = mapper.createObjectNode()
		root.put("a", "\${MISSING}")
		val ex = shouldThrow<IllegalStateException> { substituteTreeValues(root) { null } }
		ex.message!! shouldContain "MISSING"
	}
})
