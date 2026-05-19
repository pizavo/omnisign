package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the pure helpers behind the CLI's verbosity ladder:
 * [rootLogLevel], [extendedLibraryLevel], and [CliExtendedLoggers].
 */
class CliVerbosityTest : FunSpec({

    test("default keeps the configured root level (returns null)") {
        rootLogLevel(verbose = false, debug = false, extended = false) shouldBe null
    }

    test("--verbose lowers the root level to INFO") {
        rootLogLevel(verbose = true, debug = false, extended = false) shouldBe Level.INFO
    }

    test("--debug lowers the root level to DEBUG") {
        rootLogLevel(verbose = false, debug = true, extended = false) shouldBe Level.DEBUG
    }

    test("--debug-extended implies DEBUG even without --debug") {
        rootLogLevel(verbose = false, debug = false, extended = true) shouldBe Level.DEBUG
    }

    test("--debug wins over --verbose") {
        rootLogLevel(verbose = true, debug = true, extended = false) shouldBe Level.DEBUG
    }

    test("--debug-extended wins over --verbose") {
        rootLogLevel(verbose = true, debug = false, extended = true) shouldBe Level.DEBUG
    }

    test("extendedLibraryLevel is DEBUG only when extended is on") {
        extendedLibraryLevel(extended = false) shouldBe null
        extendedLibraryLevel(extended = true) shouldBe Level.DEBUG
    }

    test("CliExtendedLoggers covers DSS and Apache but not the TSL firehose") {
        CliExtendedLoggers shouldContain "eu.europa.esig"
        CliExtendedLoggers shouldContain "org.apache"
        CliExtendedLoggers.none { it.startsWith("eu.europa.esig.dss.tsl") } shouldBe true
        CliExtendedLoggers shouldNotContain "eu.europa.esig.dss.tsl.runnable"
    }
})
