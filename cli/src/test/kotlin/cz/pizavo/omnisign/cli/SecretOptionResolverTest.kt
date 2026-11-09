package cz.pizavo.omnisign.cli

import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileFacts
import cz.pizavo.omnisign.data.secret.SecretFileReader
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.io.path.createTempFile
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * Verifies where [resolveSecretOption] takes a secret from: the command line first, where `-`
 * prompts, and otherwise the environment, where `-` is the secret itself.
 *
 * The file reader accepts every file, so a secret file is read on every operating system; the
 * rules for a file are tested with the reader itself.
 */
class SecretOptionResolverTest : FunSpec({

	val directory = tempdir().toPath()
	val variable = "OMNISIGN_TEST_PASSWORD"
	val fileVariable = "OMNISIGN_TEST_PASSWORD_FILE"
	val callback = mockk<PasswordCallback>()
	val secretReader = EnvironmentSecretReader(
		SecretFileReader(
			probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
			effectiveUid = { 1000 },
		),
		osName = "Linux",
	)

	/** Resolve the test secret from [commandLineValue] and an environment that holds only [variables]. */
	fun resolve(commandLineValue: String?, vararg variables: Pair<String, String>) = resolveSecretOption(
		commandLineValue = commandLineValue,
		variable = variable,
		environment = mapOf(*variables)::get,
		secretReader = secretReader,
		passwordCallback = callback,
		prompt = "Test password",
	)

	beforeTest {
		clearMocks(callback)
		every { callback.requestPassword("Test password", any()) } returns "prompted"
	}

	test("takes the value given on the command line") {
		resolve("given").shouldBeRight()?.value shouldBe "given"
	}

	test("prompts when the command line gives '-'") {
		resolve(PASSWORD_PROMPT_SENTINEL).shouldBeRight()?.value shouldBe "prompted"
	}

	test("prefers the command line to the environment, without reading it") {
		val missing = directory.resolve("missing").toString()

		resolve("given", variable to "inherited", fileVariable to missing).shouldBeRight()?.value shouldBe "given"
	}

	test("takes the secret from the variable without a value on the command line") {
		resolve(null, variable to "inherited").shouldBeRight()?.value shouldBe "inherited"
	}

	test("takes the secret from the file the variable with _FILE names") {
		val file = createTempFile(directory, "secret").also { it.writeText("from-file\n") }

		resolve(null, fileVariable to file.toString()).shouldBeRight()?.value shouldBe "from-file"
	}

	test("takes '-' from the environment as the secret, without prompting") {
		resolve(null, variable to PASSWORD_PROMPT_SENTINEL).shouldBeRight()?.value shouldBe PASSWORD_PROMPT_SENTINEL

		verify(exactly = 0) { callback.requestPassword(any(), any()) }
	}

	test("finds no secret when neither the command line nor the environment sets one") {
		resolve(null).shouldBeRight().shouldBeNull()
	}

	test("passes on why the environment's secret was refused") {
		val file = createTempFile(directory, "secret").also { it.writeText("from-file") }

		resolve(null, variable to "inherited", fileVariable to file.toString())
			.shouldBeLeft().message shouldContain "Set only one of them"
	}
})
