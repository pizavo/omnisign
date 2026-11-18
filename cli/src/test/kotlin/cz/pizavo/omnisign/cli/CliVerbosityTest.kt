package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests for the helpers behind the CLI's verbosity ladder:
 * [rootLogLevel], [extendedLibraryLevel], [CliExtendedLoggers] and
 * [applyLogLevels], which applies them to a Logback context.
 */
class CliVerbosityTest : FunSpec({

    /** HttpClient's loggers that write every request header, HTTP Basic credentials included, at DEBUG. */
    val headerLoggers = listOf("org.apache.hc.client5.http.headers", "org.apache.hc.client5.http.wire")

    /** Return the loggers the CLI's `logback.xml` gives a level, the root logger among them, mapped to that level. */
    fun configuredLevels(): Map<String, Level> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = CliVerbosityTest::class.java.getResourceAsStream("/logback.xml").shouldNotBeNull().use {
            factory.newDocumentBuilder().parse(it)
        }
        val loggers = document.getElementsByTagName("logger")
        val root = document.getElementsByTagName("root").item(0).shouldNotBeNull() as Element
        return (0 until loggers.length).map { loggers.item(it) as Element }
            .filter { it.hasAttribute("level") }
            .associate { it.getAttribute("name") to Level.toLevel(it.getAttribute("level")) } +
            (Logger.ROOT_LOGGER_NAME to Level.toLevel(root.getAttribute("level")))
    }

    /** Return a new Logback context whose loggers hold the levels the CLI's `logback.xml` gives them. */
    fun configuredContext(): LoggerContext = LoggerContext().also { context ->
        for ((name, level) in configuredLevels()) context.getLogger(name).level = level
    }

    test("CliExtendedLoggers lists every DSS and Apache logger logback.xml gives a level, apart from the TSL ones and HttpClient's header and wire loggers") {
        val expected = configuredLevels().keys.filter { name ->
            listOf("eu.europa.esig", "org.apache").any { name == it || name.startsWith("$it.") } &&
                !name.startsWith("eu.europa.esig.dss.tsl.") && name !in headerLoggers
        }

        expected shouldContain "org.apache.pdfbox.pdfparser"
        CliExtendedLoggers.toSet() shouldBe expected.toSet()
    }

    test("--debug-extended leaves HttpClient's header and wire loggers at the INFO logback.xml pins them to, while the rest of Apache logs at DEBUG") {
        val configured = configuredLevels()
        for (name in headerLoggers) {
            configured[name] shouldBe Level.INFO
            CliExtendedLoggers shouldNotContain name
        }

        val context = configuredContext()
        applyLogLevels(context, verbose = false, debug = false, extended = true)

        for (name in headerLoggers) context.getLogger(name).isDebugEnabled shouldBe false
        context.getLogger("org.apache.hc.client5.http.impl.classic.InternalHttpClient").isDebugEnabled shouldBe true
        context.getLogger("eu.europa.esig.dss.service.http.commons.CommonsDataLoader").isDebugEnabled shouldBe true
    }

    test("without a flag every level stays as logback.xml gives it, and --verbose lowers only the root, to INFO") {
        val configured = configuredLevels()
        configured[Logger.ROOT_LOGGER_NAME] shouldBe Level.WARN

        val quiet = configuredContext()
        applyLogLevels(quiet, verbose = false, debug = false, extended = false)
        for ((name, level) in configured) quiet.getLogger(name).level shouldBe level

        val verbose = configuredContext()
        applyLogLevels(verbose, verbose = true, debug = false, extended = false)
        verbose.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.INFO
        for ((name, level) in configured - Logger.ROOT_LOGGER_NAME) verbose.getLogger(name).level shouldBe level
    }

    test("--debug lowers the root to DEBUG and leaves the library loggers at the levels logback.xml gives them") {
        val context = configuredContext()
        context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.WARN
        applyLogLevels(context, verbose = false, debug = true, extended = false)

        context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG
        context.getLogger("org.apache").level shouldBe Level.WARN
        context.getLogger("eu.europa.esig").level shouldBe Level.ERROR
        context.getLogger("org.apache.hc.client5.http.impl.classic.InternalHttpClient").isDebugEnabled shouldBe false
    }

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
