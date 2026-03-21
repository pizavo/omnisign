package cz.pizavo.omnisign.data.repository

import org.slf4j.LoggerFactory

/**
 * Captures WARN-level SLF4J log messages emitted by DSS internal classes during
 * a signing, extension, or validation operation.
 *
 * Many DSS diagnostic messages (e.g., malformed certificate policy OIDs in third-party
 * TSA certificates, per-certificate revocation lookup failures) are logged directly
 * via SLF4J and do **not** go through the [eu.europa.esig.dss.alert.StatusAlert]
 * mechanism wired into [eu.europa.esig.dss.spi.validation.CommonCertificateVerifier].
 * These messages are suppressed by the logback configuration to avoid noisy output,
 * but they still carry useful diagnostic information the user should see as structured
 * warnings.
 *
 * This class uses the Logback API (via reflection so the shared module does not need
 * a compile-time dependency on `logback-classic`) to:
 * 1. Temporarily lower the effective level of the monitored loggers from ERROR to WARN.
 * 2. Disable additivity so WARN events do not propagate to the root appender (stderr).
 * 4. On [stop], restore the original levels and additivity, detach the appender, and
 *    return the collected messages.
 *
 * If Logback is not the runtime SLF4J, binding the capture silently does nothing.
 *
 * Typical lifecycle:
 * ```
 * val capture = DssLogCapture()
 * capture.start()
 * try {
 *     // ... run DSS operation ...
 * } finally {
 *     val warnings = capture.stop()
 * }
 * ```
 *
 * @param loggerNames DSS logger names to monitor.  Defaults to the loggers whose
 *   output is suppressed by the project's logback configuration.
 */
class DssLogCapture(
	private val loggerNames: List<String> = DEFAULT_LOGGER_NAMES
) {

	@Volatile
	private var appenderHandle: Any? = null

	@Volatile
	private var savedState: List<LoggerState> = emptyList()

	/**
	 * Attach a capturing appender to each configured DSS logger, temporarily
	 * lowering their effective level to WARN and disabling additivity.
	 *
	 * If the runtime SLF4J binding is not Logback, this is a no-op.
	 */
	fun start() {
		try {
			val logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger")
			val levelClass = Class.forName("ch.qos.logback.classic.Level")
			val warnLevel = levelClass.getField("WARN").get(null)

			val appenderClass = Class.forName("ch.qos.logback.core.read.ListAppender")
			val appender = appenderClass.getDeclaredConstructor().newInstance()
			appenderClass.getMethod("setName", String::class.java)
				.invoke(appender, APPENDER_NAME)
			appenderClass.getMethod("start").invoke(appender)

			val addAppenderMethod = logbackLoggerClass.getMethod(
				"addAppender", Class.forName("ch.qos.logback.core.Appender")
			)
			val getLevelMethod = logbackLoggerClass.getMethod("getLevel")
			val setLevelMethod = logbackLoggerClass.getMethod("setLevel", levelClass)
			val isAdditiveMethod = logbackLoggerClass.getMethod("isAdditive")
			val setAdditiveMethod = logbackLoggerClass.getMethod("setAdditive", Boolean::class.java)

			val states = loggerNames.mapNotNull { name ->
				val slf4jLogger = LoggerFactory.getLogger(name)
				if (!logbackLoggerClass.isInstance(slf4jLogger)) return@mapNotNull null

				val originalLevel = getLevelMethod.invoke(slf4jLogger)
				val originalAdditive = isAdditiveMethod.invoke(slf4jLogger) as Boolean

				setLevelMethod.invoke(slf4jLogger, warnLevel)
				setAdditiveMethod.invoke(slf4jLogger, false)
				addAppenderMethod.invoke(slf4jLogger, appender)

				LoggerState(slf4jLogger, originalLevel, originalAdditive)
			}

			appenderHandle = appender
			savedState = states
		} catch (_: Exception) {
			// Logback not available or API changed — capture is silently disabled.
		}
	}

	/**
	 * Detach the capturing appender, restore original logger levels and additivity,
	 * and return all collected warning messages.
	 *
	 * Duplicate messages are removed.  Messages below WARN level (if any leaked
	 * through) are filtered out.
	 */
	fun stop(): List<String> {
		val appender = appenderHandle ?: return emptyList()
		appenderHandle = null

		try {
			val logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger")
			val levelClass = Class.forName("ch.qos.logback.classic.Level")
			val detachMethod = logbackLoggerClass.getMethod(
				"detachAppender", Class.forName("ch.qos.logback.core.Appender")
			)
			val setLevelMethod = logbackLoggerClass.getMethod("setLevel", levelClass)
			val setAdditiveMethod = logbackLoggerClass.getMethod("setAdditive", Boolean::class.java)

			for (state in savedState) {
				detachMethod.invoke(state.logger, appender)
				setLevelMethod.invoke(state.logger, state.originalLevel)
				setAdditiveMethod.invoke(state.logger, state.originalAdditive)
			}
			savedState = emptyList()

			appender.javaClass.getMethod("stop").invoke(appender)

			@Suppress("UNCHECKED_CAST")
			val events = appender.javaClass.getField("list").get(appender) as? List<Any>
				?: return emptyList()

			val iLoggingEventClass = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent")
			val getMessageMethod = iLoggingEventClass.getMethod("getFormattedMessage")
			val getLevelMethod = iLoggingEventClass.getMethod("getLevel")
			val warnLevel = levelClass.getField("WARN").get(null)
			val toIntMethod = levelClass.getMethod("toInt")
			val warnInt = toIntMethod.invoke(warnLevel) as Int

			return events.mapNotNull { event ->
				val eventLevel = getLevelMethod.invoke(event) ?: return@mapNotNull null
				val eventInt = toIntMethod.invoke(eventLevel) as Int
				if (eventInt < warnInt) return@mapNotNull null
				getMessageMethod.invoke(event) as? String
			}.filter { it.isNotBlank() }.distinct()
		} catch (_: Exception) {
			return emptyList()
		}
	}

	/**
	 * Saved logger state for restoration in [stop].
	 */
	private data class LoggerState(
		val logger: Any,
		val originalLevel: Any?,
		val originalAdditive: Boolean,
	)

	companion object {
		private const val APPENDER_NAME = "omnisign-dss-capture"

		/**
		 * DSS loggers whose WARN output is suppressed by the project logback configuration
		 * but whose messages should be surfaced as structured user-facing warnings.
		 */
		val DEFAULT_LOGGER_NAMES = listOf(
			"eu.europa.esig.dss.spi.CertificateExtensionsUtils",
			"eu.europa.esig.dss.spi.validation.SignatureValidationContext",
			"eu.europa.esig.dss.spi.validation.RevocationDataVerifier",
			"eu.europa.esig.dss.spi.validation.TimestampTokenVerifier",
			"eu.europa.esig.dss.alert.handler.LogHandler",
		)
	}
}







