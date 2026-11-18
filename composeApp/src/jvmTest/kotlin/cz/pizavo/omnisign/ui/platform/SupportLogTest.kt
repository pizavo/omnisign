package cz.pizavo.omnisign.ui.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests for the testable seams of the JVM [SupportLog] actual: the
 * Logback level mapping, the library loggers extended logging lowers and those it
 * must leave alone, and the persisted-preferences round-trip.
 */
class SupportLogTest : FunSpec({

    /** HttpClient's loggers that write every request header, HTTP Basic credentials included, at DEBUG. */
    val headerLoggers = listOf("org.apache.hc.client5.http.headers", "org.apache.hc.client5.http.wire")

    /** Return the loggers the desktop's `logback.xml` gives a level, mapped to that level. */
    fun configuredLoggers(): Map<String, Level> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = SupportLogTest::class.java.getResourceAsStream("/logback.xml").shouldNotBeNull().use {
            factory.newDocumentBuilder().parse(it)
        }
        val loggers = document.getElementsByTagName("logger")
        return (0 until loggers.length).map { loggers.item(it) as Element }
            .filter { it.hasAttribute("level") }
            .associate { it.getAttribute("name") to Level.toLevel(it.getAttribute("level")) }
    }

    test("extended logging lowers every DSS and Apache logger logback.xml gives a level, apart from the TSL ones and HttpClient's header and wire loggers, and restores that level") {
        val expected = configuredLoggers().filterKeys { name ->
            listOf("eu.europa.esig", "org.apache").any { name == it || name.startsWith("$it.") } &&
                !name.startsWith("eu.europa.esig.dss.tsl.") && name !in headerLoggers
        }

        expected shouldContain ("org.apache.pdfbox.pdfparser" to Level.ERROR)
        LIBRARY_BASELINE shouldBe expected
    }

    test("extended logging leaves HttpClient's header and wire loggers at the INFO logback.xml pins them to, while the rest of Apache logs at DEBUG until it is turned off") {
        val configured = configuredLoggers()
        for (name in headerLoggers) {
            configured[name] shouldBe Level.INFO
            LIBRARY_BASELINE.keys shouldNotContain name
        }
        val context = LoggerContext()
        for ((name, level) in configured) context.getLogger(name).level = level

        applyLevels(debug = true, extended = true, context = context)
        for (name in headerLoggers) context.getLogger(name).isDebugEnabled shouldBe false
        context.getLogger("org.apache.hc.client5.http.impl.classic.InternalHttpClient").isDebugEnabled shouldBe true
        context.getLogger("eu.europa.esig.dss.service.http.commons.CommonsDataLoader").isDebugEnabled shouldBe true
        context.getLogger("cz.pizavo.omnisign").level shouldBe Level.DEBUG

        applyLevels(debug = true, extended = false, context = context)
        context.getLogger("org.apache.hc.client5.http.impl.classic.InternalHttpClient").isDebugEnabled shouldBe false
        context.getLogger("org.apache").level shouldBe Level.WARN
        for (name in headerLoggers) context.getLogger(name).level shouldBe Level.INFO
        context.getLogger("cz.pizavo.omnisign").level shouldBe Level.DEBUG

        applyLevels(debug = false, extended = false, context = context)
        context.getLogger("cz.pizavo.omnisign").level shouldBe null
        context.getLogger("org.apache").level shouldBe Level.WARN
    }

    test("app logger is DEBUG when debug logging is on, inherits root when off") {
        appLoggerLevel(debug = true) shouldBe Level.DEBUG
        appLoggerLevel(debug = false) shouldBe null
    }

    test("library logger is DEBUG only when both debug and extended are on") {
        libraryLoggerLevel(debug = true, extended = true, baseline = Level.ERROR) shouldBe Level.DEBUG
        libraryLoggerLevel(debug = true, extended = false, baseline = Level.ERROR) shouldBe Level.ERROR
        libraryLoggerLevel(debug = false, extended = true, baseline = Level.WARN) shouldBe Level.WARN
        libraryLoggerLevel(debug = false, extended = false, baseline = Level.WARN) shouldBe Level.WARN
    }

    test("support flags round-trip and a missing key reads as false") {
        val dir = Files.createTempDirectory("omnisign-support-test")
        val path = dir.resolve("support.properties")
        try {
            readSupportFlag(path, "debug") shouldBe false

            writeSupportFlag(path, "debug", true)
            readSupportFlag(path, "debug") shouldBe true
            readSupportFlag(path, "extended") shouldBe false

            writeSupportFlag(path, "extended", true)
            readSupportFlag(path, "debug") shouldBe true
            readSupportFlag(path, "extended") shouldBe true

            writeSupportFlag(path, "debug", false)
            readSupportFlag(path, "debug") shouldBe false
            readSupportFlag(path, "extended") shouldBe true
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
})
