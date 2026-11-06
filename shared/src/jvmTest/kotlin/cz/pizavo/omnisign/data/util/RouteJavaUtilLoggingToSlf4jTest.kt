package cz.pizavo.omnisign.data.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import java.util.logging.Handler
import java.util.logging.Logger as JulLogger

/**
 * Verifies what OmniSign relies on from [routeJavaUtilLoggingToSlf4j]: the SLF4J bridge ends up as
 * the JUL root logger's only handler, however often the function is called; a JUL record then reaches
 * the Logback logger of the same name, whose level decides whether it is kept, as the level of
 * `org.verapdf` decides for veraPDF's warnings; and JUL's own threshold still stops a record below
 * it, which keeps the debug output of JAXB and JNA out of the CLI's `--debug`. The root logger's
 * handlers are process-global, so each test restores them afterwards.
 */
class RouteJavaUtilLoggingToSlf4jTest : FunSpec({

	val parserLogger = "org.verapdf.pd.PDPage"
	val parserWarning = "Missing /Resources entry or inherited resources in the page dictionary"
	val julRoot = JulLogger.getLogger("")
	var previousHandlers = emptyList<Handler>()

	/**
	 * The events that reach Logback's `org.verapdf` logger while [block] logs through the JUL logger
	 * of veraPDF's parser, with the Logback logger at [level] until it returns.
	 */
	fun eventsLoggedDuring(level: Level, block: JulLogger.() -> Unit): List<ILoggingEvent> {
		val logbackLogger = LoggerFactory.getLogger("org.verapdf") as Logger
		val previousLevel = logbackLogger.level
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}
		logbackLogger.level = level
		try {
			JulLogger.getLogger(parserLogger).block()
		} finally {
			logbackLogger.level = previousLevel
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
		return appender.list.toList()
	}

	beforeTest {
		previousHandlers = julRoot.handlers.toList()
		routeJavaUtilLoggingToSlf4j()
	}

	afterTest {
		julRoot.handlers.forEach(julRoot::removeHandler)
		previousHandlers.forEach(julRoot::addHandler)
	}

	test("leaves the SLF4J bridge as the JUL root logger's only handler, however often it is called") {
		routeJavaUtilLoggingToSlf4j()

		julRoot.handlers.map { it::class } shouldContainExactly listOf(SLF4JBridgeHandler::class)
	}

	test("passes a JUL warning to the Logback logger of the same name as WARN") {
		val events = eventsLoggedDuring(Level.TRACE) { warning(parserWarning) }

		events.map { Triple(it.loggerName, it.level, it.formattedMessage) } shouldContainExactly
			listOf(Triple(parserLogger, Level.WARN, parserWarning))
	}

	test("a Logback logger kept at ERROR, as org.verapdf is, drops a JUL warning and keeps a severe record") {
		val failure = "The document cannot be parsed"

		val events = eventsLoggedDuring(Level.ERROR) {
			warning(parserWarning)
			severe(failure)
		}

		events.map { it.level to it.formattedMessage } shouldContainExactly listOf(Level.ERROR to failure)
	}

	test("leaves out a record below JUL's threshold, even from a Logback logger open to everything") {
		eventsLoggedDuring(Level.TRACE) { fine("A detail of the parser") }.shouldBeEmpty()
	}
})
