package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Verifies [SigningConfigLoader] parsing, env-var substitution, profile-source folding
 * (inline / files / directories), and the fail-fast contract on misconfiguration.
 *
 * YAML fixtures are written column-aligned at the left margin so their literal indentation
 * is the YAML structure, independent of the surrounding Kotlin indentation.
 */
class SigningConfigLoaderTest : FunSpec({

	val loader = SigningConfigLoader()

	fun newDir(): File = createTempDirectory("signing-loader-test-").toFile()

	fun File.put(name: String, content: String): File =
		resolve(name).apply { parentFile.mkdirs(); writeText(content) }

	test("load(null) returns built-in defaults with no profiles") {
		val config = loader.load(null)
		config.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
		config.profiles.shouldBeEmpty()
	}

	test("load parses global overrides and inline profiles keyed by name") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  defaultHashAlgorithm: SHA512
profiles:
  inline:
    - name: fast
      hashAlgorithm: SHA256
    - name: archival
      signatureLevel: PADES_BASELINE_LTA
""")
		val config = loader.load(file.absolutePath)
		config.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
		config.profiles.keys shouldContainExactlyInAnyOrder setOf("fast", "archival")
		config.profiles.getValue("fast").hashAlgorithm shouldBe HashAlgorithm.SHA256
		config.profiles.getValue("archival").signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("load rejects a profile explicitly configured with PADES_BASELINE_T") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
profiles:
  inline:
    - name: timestamped
      signatureLevel: PADES_BASELINE_T
""")
		val message = shouldThrow<IllegalArgumentException> { loader.load(file.absolutePath) }.message
		message.shouldNotBeNull()
		message shouldContain "PADES_BASELINE_T"
		message shouldContain "timestamped"
	}

	test("load rejects a global default of PADES_BASELINE_T") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  defaultSignatureLevel: PADES_BASELINE_T
""")
		val message = shouldThrow<IllegalArgumentException> { loader.load(file.absolutePath) }.message
		message.shouldNotBeNull()
		message shouldContain "PADES_BASELINE_T"
	}

	test("load resolves a profile file referenced by a relative path") {
		val dir = newDir()
		dir.put("profiles/archival.yml", "name: archival\nsignatureLevel: PADES_BASELINE_LTA\n")
		val file = dir.put("signing.yml", """
profiles:
  files:
    - profiles/archival.yml
""")
		val config = loader.load(file.absolutePath)
		config.profiles.keys shouldContainExactlyInAnyOrder setOf("archival")
		config.profiles.getValue("archival").signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("load scans directories recursively and skips hidden entries") {
		val dir = newDir()
		dir.put("p/a.yml", "name: a\n")
		dir.put("p/nested/b.yaml", "name: b\n")
		dir.put("p/.hidden/c.yml", "name: c\n")
		dir.put("p/.skip.yml", "name: d\n")
		val file = dir.put("signing.yml", """
profiles:
  directories:
    - p
""")
		val config = loader.load(file.absolutePath)
		config.profiles.keys shouldContainExactlyInAnyOrder setOf("a", "b")
	}

	test("load fails fast on a duplicate profile name across sources") {
		val dir = newDir()
		dir.put("p/dup.yml", "name: shared\n")
		val file = dir.put("signing.yml", """
profiles:
  inline:
    - name: shared
  directories:
    - p
""")
		val ex = shouldThrow<IllegalArgumentException> { loader.load(file.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "Duplicate profile name 'shared'"
	}

	test("load fails fast on an unknown top-level key") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  defaultHashAlgorithm: SHA256
bogusKey: true
""")
		val ex = shouldThrow<UnrecognizedPropertyException> { loader.load(file.absolutePath) }
		ex.propertyName shouldBe "bogusKey"
	}

	test("load rejects an excluded AppConfig field (activeProfile)") {
		val dir = newDir()
		val file = dir.put("signing.yml", "activeProfile: fast\n")
		val ex = shouldThrow<UnrecognizedPropertyException> { loader.load(file.absolutePath) }
		ex.propertyName shouldBe "activeProfile"
	}

	test("load throws when the configured signing file is missing") {
		val ex = shouldThrow<IllegalArgumentException> {
			loader.load(File(newDir(), "absent.yml").absolutePath)
		}
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "does not exist"
	}

	test("load throws when a referenced profile file is missing") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
profiles:
  files:
    - profiles/ghost.yml
""")
		val ex = shouldThrow<IllegalArgumentException> { loader.load(file.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "ghost.yml"
	}

	test("load parses a .json signing file") {
		val dir = newDir()
		val file = dir.put(
			"signing.json",
			"""{ "global": { "defaultHashAlgorithm": "SHA512" }, "profiles": { "inline": [ { "name": "fast" } ] } }""",
		)
		val config = loader.load(file.absolutePath)
		config.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
		config.profiles.keys shouldContainExactlyInAnyOrder setOf("fast")
	}

	test("load expands an environment variable in a scalar value") {
		val ref = "\${OMNISIGN_OCSP_TIMEOUT}"
		val envLoader = SigningConfigLoader { if (it == "OMNISIGN_OCSP_TIMEOUT") "60000" else null }
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  ocsp:
    timeout: "$ref"
""")
		envLoader.load(file.absolutePath).global.ocsp.timeout shouldBe 60000
	}

	test("load reads the TSA password from the environment via password") {
		val ref = "\${OMNISIGN_TSA_PASSWORD}"
		val envLoader = SigningConfigLoader { if (it == "OMNISIGN_TSA_PASSWORD") "s3cr3t" else null }
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  timestampServer:
    url: "http://tsa.example.org/tsr"
    username: "omnisign"
    password: "$ref"
""")
		val ts = envLoader.load(file.absolutePath).global.timestampServer
		ts.shouldNotBeNull()
		ts.runtimePassword?.value shouldBe "s3cr3t"
	}

	test("load fails fast when a referenced environment variable is unset") {
		val ref = "\${OMNISIGN_MISSING}"
		val envLoader = SigningConfigLoader { null }
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  ocsp:
    timeout: "$ref"
""")
		val ex = shouldThrow<IllegalStateException> { envLoader.load(file.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "OMNISIGN_MISSING"
	}

	test("load rejects the OS-keyring credentialKey on the server") {
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  timestampServer:
    url: "http://tsa.example.org/tsr"
    credentialKey: "omnisign"
""")
		val ex = shouldThrow<IllegalArgumentException> { loader.load(file.absolutePath) }
		ex.message.shouldNotBeNull()
		ex.message!! shouldContain "credentialKey"
	}

	test("does not expand environment variables inside comments") {
		val ref = "\${OMNISIGN_UNSET_IN_COMMENT}"
		val dir = newDir()
		val file = dir.put("signing.yml", """
global:
  defaultHashAlgorithm: SHA256
# password: "$ref"
""")
		loader.load(file.absolutePath).global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
	}

	test("the shipped signing.example.yml parses with its uncommented defaults") {
		val text = checkNotNull(
			SigningConfigLoaderTest::class.java.getResource("/signing.example.yml"),
		) { "signing.example.yml not found on the test classpath" }.readText()
		val file = newDir().put("signing.yml", text)
		val config = loader.load(file.absolutePath)
		config.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
		config.profiles.keys shouldContainExactlyInAnyOrder setOf("fast", "archival")
	}
})
