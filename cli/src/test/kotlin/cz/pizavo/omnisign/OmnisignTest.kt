package cz.pizavo.omnisign

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.cli.CliExtendedLoggers
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.slf4j.LoggerFactory

/**
 * Verifies root command instantiation, help output, global flags, version, subcommand registration, and the logger
 * levels the verbosity flags set when the root command runs.
 */
class OmnisignTest : FunSpec({

	/**
	 * Run [block] on the JVM's Logback context and give every logger back the level it had before, so that the levels
	 * the root command sets do not reach the specs that run after this one.
	 */
	fun withRestoredLogLevels(block: (LoggerContext) -> Unit) {
		val context = LoggerFactory.getILoggerFactory() as LoggerContext
		val levels = context.loggerList.associateWith { it.level }
		try {
			block(context)
		} finally {
			for (logger in context.loggerList) logger.level = levels[logger]
		}
	}

	/**
	 * Run the root command with [flags] before a subcommand that does nothing, because Clikt runs [Omnisign.run], where
	 * the flags take effect, only when a subcommand follows.
	 */
	fun runRootCommand(flags: String) {
		val noOp = object : CliktCommand(name = "noop") {
			override fun run() = Unit
		}
		Omnisign().subcommands(noOp).test("$flags noop".trim()).statusCode shouldBe 0
	}

	val headerLoggers = listOf("org.apache.hc.client5.http.headers", "org.apache.hc.client5.http.wire")

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

	test("without a verbosity flag the root and the library loggers keep their configured levels") {
		withRestoredLogLevels { context ->
			val names = listOf(Logger.ROOT_LOGGER_NAME) + CliExtendedLoggers
			val configuredLevels = names.associateWith { context.getLogger(it).level }
			runRootCommand("")
			names.associateWith { context.getLogger(it).level } shouldBe configuredLevels
		}
	}

	test("--verbose raises the root logger to INFO and leaves the library loggers as configured") {
		withRestoredLogLevels { context ->
			val libraryLevels = CliExtendedLoggers.associateWith { context.getLogger(it).level }
			runRootCommand("--verbose")
			context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.INFO
			CliExtendedLoggers.associateWith { context.getLogger(it).level } shouldBe libraryLevels
		}
	}

	test("--debug lowers the root logger to DEBUG and leaves the library loggers as configured") {
		withRestoredLogLevels { context ->
			val libraryLevels = CliExtendedLoggers.associateWith { context.getLogger(it).level }
			runRootCommand("--debug")
			context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG
			CliExtendedLoggers.associateWith { context.getLogger(it).level } shouldBe libraryLevels
		}
	}

	test("--debug-extended lowers the root and the library loggers to DEBUG but not HttpClient's header loggers") {
		withRestoredLogLevels { context ->
			val headerLevels = headerLoggers.associateWith { context.getLogger(it).level }
			runRootCommand("--debug-extended")
			context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG
			for (name in CliExtendedLoggers) withClue(name) { context.getLogger(name).level shouldBe Level.DEBUG }
			headerLoggers.associateWith { context.getLogger(it).level } shouldBe headerLevels
			headerLevels.values.toSet() shouldBe setOf(Level.INFO)
		}
	}
})

