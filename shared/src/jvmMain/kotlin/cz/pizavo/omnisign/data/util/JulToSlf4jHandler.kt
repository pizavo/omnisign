package cz.pizavo.omnisign.data.util

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.logging.Level as JulLevel

/**
 * A `java.util.logging` handler that passes each record on to the SLF4J logger of the same name, so
 * a library that logs through JUL goes through the Logback configuration like the rest of the
 * application, instead of JUL's console handler printing it to stderr in a two-line format of its
 * own.
 *
 * The library this is for is veraPDF, which DSS runs to check PDF/A conformance: its parser warns
 * about every structural fault it tolerates in a document, as PDFBox's does, and each Logback
 * configuration keeps both at ERROR.
 *
 * A record takes the most severe SLF4J level whose JUL counterpart it reaches, with FINE standing
 * for DEBUG and FINEST for TRACE: SEVERE gives ERROR, WARNING gives WARN, INFO gives INFO, CONFIG
 * and FINE give DEBUG, and FINER and FINEST give TRACE. JUL applies its own threshold before the
 * handler sees a record, INFO unless configured otherwise, so a DEBUG root logger does not pull in
 * the debug output of the JDK and of the other libraries that log through JUL, such as JAXB and JNA.
 */
class JulToSlf4jHandler : Handler() {

	init {
		formatter = SimpleFormatter()
	}

	/**
	 * Log [record] through the SLF4J logger named after its JUL logger, or [ANONYMOUS_LOGGER] for a
	 * record that names none, at the level [levelOf] gives it. The message is localized and its
	 * parameters are filled in by this handler's formatter, as JUL's own handlers do, and the
	 * record's exception becomes the cause.
	 */
	override fun publish(record: LogRecord?) {
		if (record == null || !isLoggable(record)) return
		KotlinLogging.logger(record.loggerName ?: ANONYMOUS_LOGGER).at(levelOf(record.level)) {
			message = formatter.formatMessage(record)
			cause = record.thrown
		}
	}

	/** Does nothing: each record is handed to SLF4J as it is published. */
	override fun flush() = Unit

	/** Does nothing: the handler holds nothing to release. */
	override fun close() = Unit

	companion object {
		/** SLF4J logger for a record whose JUL logger has no name, the one SLF4J's own bridge uses. */
		internal const val ANONYMOUS_LOGGER = "unknown.jul.logger"

		/**
		 * Route all `java.util.logging` output through SLF4J from now on, by putting one
		 * [JulToSlf4jHandler] in place of the JUL root logger's handlers, by default the console
		 * handler that prints to stderr.
		 *
		 * The root logger's handlers are process-global, so call this once at host startup, before
		 * anything logs through JUL. Calling it again still leaves a single [JulToSlf4jHandler].
		 */
		fun install() {
			val root = Logger.getLogger("")
			root.handlers.forEach(root::removeHandler)
			root.addHandler(JulToSlf4jHandler())
		}

		/**
		 * The SLF4J level of a record at the JUL [level]: the most severe one whose JUL counterpart
		 * [level] reaches.
		 */
		private fun levelOf(level: JulLevel): Level = when {
			level.intValue() >= JulLevel.SEVERE.intValue() -> Level.ERROR
			level.intValue() >= JulLevel.WARNING.intValue() -> Level.WARN
			level.intValue() >= JulLevel.INFO.intValue() -> Level.INFO
			level.intValue() >= JulLevel.FINE.intValue() -> Level.DEBUG
			else -> Level.TRACE
		}
	}
}
