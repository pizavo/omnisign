package cz.pizavo.omnisign

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Verifies root command instantiation, help output, global flags, version, and subcommand registration.
 */
class OmnisignTest : FunSpec({
	
	test("CLI should be instantiable") {
		Omnisign().shouldNotBeNull()
	}
	
	test("root command with no args prints help") {
		val result = Omnisign().test("")
		(result.output.contains("omnisign") || result.output.contains("Usage")).shouldBeTrue()
	}
	
	test("root command registers global flags") {
		val optionNames = Omnisign().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--json")
		optionNames.shouldContain("--verbose")
		optionNames.shouldContain("--quiet")
	}
	
	test("version option prints version") {
		Omnisign().test("--version").output.shouldNotBeBlank()
	}
	
	test("all subcommands are registered") {
		val subcommandNames = Omnisign().registeredSubcommands().map { it.commandName }
		subcommandNames.shouldContain("sign")
		subcommandNames.shouldContain("validate")
		subcommandNames.shouldContain("timestamp")
		subcommandNames.shouldContain("renew")
		subcommandNames.shouldContain("algorithms")
		subcommandNames.shouldContain("certificates")
		subcommandNames.shouldContain("config")
		subcommandNames.shouldContain("schedule")
	}
})

