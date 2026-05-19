package cz.pizavo.omnisign.ui.platform

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Unit tests for the testable seams of the JVM [SupportLog] actual: the
 * Logback level mapping and the persisted-preferences round-trip.
 */
class SupportLogTest : FunSpec({

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
