package cz.pizavo.omnisign.data.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.logging.LogRecord
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Verifies that [JulToSlf4jHandler] passes `java.util.logging` records to the SLF4J logger of the
 * same name, at the level its mapping gives them, with their parameters filled in and their
 * exception kept; that Logback drops a record below the level configured for that logger, as it
 * drops veraPDF's warnings; and that [JulToSlf4jHandler.install] leaves the JUL root logger with
 * that handler alone. The records are captured on Logback's `org.verapdf` logger, whose level each
 * test sets and restores.
 */
class JulToSlf4jHandlerTest : FunSpec({

	val parserLogger = "org.verapdf.pd.PDPage"
	val parserWarning = "Missing /Resources entry or inherited resources in the page dictionary"

	fun record(level: JulLevel, message: String, loggerName: String? = parserLogger) =
		LogRecord(level, message).also { it.loggerName = loggerName }

	fun eventsLoggedDuring(level: Level, loggerName: String = "org.verapdf", block: () -> Unit): List<ILoggingEvent> {
		val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
		val previousLevel = logbackLogger.level
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}
		logbackLogger.level = level
		try {
			block()
		} finally {
			logbackLogger.level = previousLevel
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
		return appender.list.toList()
	}

	test("passes a JUL warning to the SLF4J logger of the same name as WARN") {
		val events = eventsLoggedDuring(Level.TRACE) {
			JulToSlf4jHandler().publish(record(JulLevel.WARNING, parserWarning))
		}

		events.map { Triple(it.loggerName, it.level, it.formattedMessage) } shouldContainExactly
			listOf(Triple(parserLogger, Level.WARN, parserWarning))
	}

	test("gives each record the most severe SLF4J level whose JUL counterpart it reaches") {
		val julLevels = listOf(
			JulLevel.SEVERE, JulLevel.WARNING, JulLevel.INFO, JulLevel.CONFIG, JulLevel.FINE, JulLevel.FINER, JulLevel.FINEST,
		)

		val events = eventsLoggedDuring(Level.TRACE) {
			julLevels.forEach { JulToSlf4jHandler().publish(record(it, it.name)) }
		}

		events.map { it.level } shouldContainExactly
			listOf(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.DEBUG, Level.TRACE, Level.TRACE)
	}

	test("fills in the parameters of a record and keeps its exception as the cause") {
		val failure = IOException("Unexpected end of stream")
		val parameterized = record(JulLevel.SEVERE, "Page {0} of {1} cannot be parsed").apply {
			parameters = arrayOf(3, 7)
			thrown = failure
		}

		val event = eventsLoggedDuring(Level.TRACE) { JulToSlf4jHandler().publish(parameterized) }.single()

		event.formattedMessage shouldBe "Page 3 of 7 cannot be parsed"
		event.throwableProxy.className shouldBe IOException::class.java.name
		event.throwableProxy.message shouldBe failure.message
	}

	test("drops a warning from under a logger that Logback keeps at ERROR, as it keeps org.verapdf") {
		eventsLoggedDuring(Level.ERROR) {
			JulToSlf4jHandler().publish(record(JulLevel.WARNING, parserWarning))
		}.shouldBeEmpty()
	}

	test("logs a record of an anonymous JUL logger under the name SLF4J's own bridge uses") {
		val events = eventsLoggedDuring(Level.TRACE, JulToSlf4jHandler.ANONYMOUS_LOGGER) {
			JulToSlf4jHandler().publish(record(JulLevel.WARNING, "A warning without a logger", loggerName = null))
		}

		events.map { it.formattedMessage } shouldContainExactly listOf("A warning without a logger")
	}

	test("install leaves the JUL root logger with one JulToSlf4jHandler, which JUL logging then reaches") {
		val root = JulLogger.getLogger("")
		val previousHandlers = root.handlers.toList()
		try {
			JulToSlf4jHandler.install()
			JulToSlf4jHandler.install()

			root.handlers.map { it::class } shouldContainExactly listOf(JulToSlf4jHandler::class)
			eventsLoggedDuring(Level.TRACE) {
				JulLogger.getLogger(parserLogger).warning(parserWarning)
			}.map { it.formattedMessage } shouldContainExactly listOf(parserWarning)
		} finally {
			root.handlers.forEach(root::removeHandler)
			previousHandlers.forEach(root::addHandler)
		}
	}
})
