package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies that [commandLineOnly] keeps a value given on the command line and drops one Clikt read
 * from a variable, which depends on Clikt handing on each value under the name it found it by.
 */
class CommandLineOnlyTest : FunSpec({

	/** A command whose only option is taken from the command line only. */
	class Probe : CliktCommand(name = "probe") {
		val secret by option("--secret", "-s").commandLineOnly()

		init {
			context { autoEnvvarPrefix = "PROBE" }
		}

		override fun run() = Unit
	}

	test("keeps a value given on the command line") {
		val probe = Probe()

		probe.test(listOf("--secret", "s3cret")).statusCode shouldBe 0
		probe.secret shouldBe "s3cret"
	}

	test("keeps a value given under the option's short name") {
		val probe = Probe()

		probe.test(listOf("-s", "s3cret")).statusCode shouldBe 0
		probe.secret shouldBe "s3cret"
	}

	test("drops a value read from the variable Clikt infers for the option") {
		val probe = Probe()

		probe.test(emptyList(), envvars = mapOf("PROBE_SECRET" to "s3cret")).statusCode shouldBe 0
		probe.secret.shouldBeNull()
	}

	test("keeps the command line's value when the variable is set too") {
		val probe = Probe()

		probe.test(listOf("--secret", "given"), envvars = mapOf("PROBE_SECRET" to "inherited")).statusCode shouldBe 0
		probe.secret shouldBe "given"
	}
})
