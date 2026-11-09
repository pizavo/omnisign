package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.OperationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Behavioral tests for [failWith], through which CLI commands report an operation that failed.
 *
 * The exit status is what a script relies on, and the lines on stderr are what a reader relies on,
 * so both are pinned: the summary comes before the error's message unless it is that message, the
 * details follow it, and a cause that only repeats the details is left out.
 */
class CommandFailureTest : FunSpec({

	/** Run a command that reports [error] through [failWith], with [summary], and return the result. */
	fun failingWith(error: OperationError, summary: String? = null): CliktCommandTestResult =
		object : CliktCommand(name = "failing") {
			override fun run() {
				failWith(error, summary)
			}
		}.test(emptyList())

	/** The non-empty lines the command printed on stderr. */
	fun CliktCommandTestResult.errorLines(): List<String> = stderr.lines().filter { it.isNotEmpty() }

	test("exits with status 1 and prints nothing on stdout") {
		val result = failingWith(ConfigurationError.profileNotFound("ghost"))

		result.statusCode shouldBe 1
		result.stdout shouldBe ""
	}

	test("prints the message alone when there is no summary") {
		val result = failingWith(ConfigurationError.profileNotFound("ghost"))

		result.errorLines() shouldBe listOf("❌ Profile 'ghost' does not exist")
	}

	test("puts the summary before the message") {
		val result = failingWith(ConfigurationError.saveFailed(), summary = "Failed to save profile")

		result.errorLines() shouldBe listOf("❌ Failed to save profile: Failed to save configuration")
	}

	test("prints a summary that is the message itself only once") {
		val result = failingWith(ConfigurationError.saveFailed(), summary = "Failed to save configuration")

		result.errorLines() shouldBe listOf("❌ Failed to save configuration")
	}

	test("follows the message with the details and a cause that adds to them") {
		val result = failingWith(
			ConfigurationError.loadFailed(
				details = "config file is not valid JSON",
				cause = RuntimeException("Unexpected token at offset 42"),
			),
		)

		result.errorLines() shouldBe listOf(
			"❌ Failed to load configuration",
			"Details: config file is not valid JSON",
			"Cause: Unexpected token at offset 42",
		)
	}

	test("leaves out a cause that only repeats the details") {
		val message = "config file is not valid JSON"

		val result = failingWith(ConfigurationError.loadFailed(details = message, cause = RuntimeException(message)))

		result.errorLines() shouldBe listOf("❌ Failed to load configuration", "Details: $message")
	}

	test("prints the cause of an error that carries no details") {
		val result = failingWith(ConfigurationError.loadFailed(cause = RuntimeException("disk unplugged")))

		result.errorLines() shouldBe listOf("❌ Failed to load configuration", "Cause: disk unplugged")
	}
})
