package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Verifies [attachLogFileAppender]: it writes the root log to the requested
 * file (creating parent directories) and reports failure for an unusable path,
 * always cleaning the appender off the global root logger afterwards.
 */
class CliFileLoggingTest : FunSpec({

    fun detachAppender() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val appender = root.getAppender(CliLogFileAppenderName)
        root.detachAppender(CliLogFileAppenderName)
        appender?.stop()
    }

    test("writes the root log to the given file, creating parent directories") {
        val dir = Files.createTempDirectory("omnisign-cli-log-test")
        val target = dir.resolve("nested").resolve("run.log")
        val marker = "cli-log-file-marker-${System.nanoTime()}"
        try {
            attachLogFileAppender(target.toString()) shouldBe true

            LoggerFactory.getLogger("cz.pizavo.omnisign.cli.CliFileLoggingTest").warn(marker)
            detachAppender()

            Files.exists(target) shouldBe true
            Files.readString(target) shouldContain marker
        } finally {
            detachAppender()
            Files.deleteIfExists(target)
            Files.deleteIfExists(target.parent)
            Files.deleteIfExists(dir)
        }
    }

    test("returns false when the path cannot be opened") {
        val dir = Files.createTempDirectory("omnisign-cli-log-test")
        val fileAsParent = Files.createFile(dir.resolve("not-a-dir"))
        val impossible = fileAsParent.resolve("sub").resolve("run.log")
        try {
            attachLogFileAppender(impossible.toString()) shouldBe false
        } finally {
            detachAppender()
            Files.deleteIfExists(fileAsParent)
            Files.deleteIfExists(dir)
        }
    }
})
