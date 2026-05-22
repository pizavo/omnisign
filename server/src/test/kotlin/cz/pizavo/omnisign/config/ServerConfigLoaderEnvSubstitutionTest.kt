package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Verifies that [ServerConfigLoader] applies value-only environment substitution: `${NAME}`
 * in a YAML value is expanded, while a placeholder in a comment is left untouched (so a
 * commented reference to an unset variable cannot abort startup).
 */
class ServerConfigLoaderEnvSubstitutionTest : FunSpec({

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
})
