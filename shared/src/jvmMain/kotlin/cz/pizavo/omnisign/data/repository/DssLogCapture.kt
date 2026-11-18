package cz.pizavo.omnisign.data.repository

import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Captures the WARN and ERROR messages that **any** DSS internal class logs through SLF4J while
 * a signing or extension operation runs, and gives each operation only the messages logged on
 * its own thread.
 *
 * Many DSS diagnostic messages (e.g., malformed certificate policy OIDs in third-party
 * TSA certificates, per-certificate revocation lookup failures, OCSP retrieval errors)
 * are logged directly via SLF4J and do **not** go through the
 * [eu.europa.esig.dss.alert.StatusAlert] mechanism wired into
 * [eu.europa.esig.dss.spi.validation.CommonCertificateVerifier].
 *
 * Rather than whitelisting individual child loggers (which silently misses any new DSS
 * logger), this class works on the **parent** `eu.europa.esig` logger. While at least one
 * [Session] is open, that logger is lowered to WARN, made non-additive and given a routing
 * appender. Child loggers that do not carry their own explicit level inherit WARN, and their
 * events propagate upward to the routing appender via Logback's default additivity.
 *
 * The routing appender records each event in the sessions opened on the thread that logged it.
 * DSS runs an operation on the thread that calls it, so operations that overlap, such as two
 * signing requests on the server, each get their own warnings and none of the other's. A
 * session therefore has to be opened on the thread that runs the operation, right before the
 * DSS calls, with no suspension before [Session.stop]. The first session to open saves the
 * logger's level and additivity, and the last one to close restores them, so sessions may open
 * and close in any order.
 *
 * The routing appender also hands every event on to the appenders above `eu.europa.esig` when
 * the logger that emitted it would have let it through without the capture, unless
 * `eu.europa.esig` was not additive to begin with. The configured log outputs therefore keep the
 * DSS lines they show outside a capture, such as ERROR events with their stack traces, whichever
 * thread logged them, and get none of the warnings only the capture lets through.
 *
 * Loggers listed in [EXCLUDED_PREFIXES] (e.g., TSL-related loggers whose output is
 * extremely verbose and handled through a separate TL-warning mechanism) retain their
 * explicit ERROR level set in the logback configuration, and sessions do not record them.
 *
 * This class uses the Logback API via reflection so the shared module does not need
 * a compile-time dependency on `logback-classic`. If Logback is not the runtime SLF4J
 * binding, or its API does not match, sessions record nothing and the loggers are left alone.
 *
 * The logger is process-wide, so one instance has to serve every operation in the process: it
 * is registered as a Koin single and injected into each repository that runs DSS operations.
 * Separate instances do not coordinate with each other.
 *
 * Typical lifecycle:
 * ```
 * val session = logCapture.start()
 * try {
 *     // ... run DSS operation on this thread ...
 *     val warnings = session.stop()
 * } finally {
 *     session.stop()
 * }
 * ```
 *
 * @param loggerName Name of the logger the routing appender is attached to.
 *   Defaults to [DSS_LOGGER_NAME].
 * @param excludedPrefixes Logger-name prefixes whose events sessions do not record.
 *   Defaults to [EXCLUDED_PREFIXES].
 */
class DssLogCapture(
	private val loggerName: String = DSS_LOGGER_NAME,
	private val excludedPrefixes: List<String> = EXCLUDED_PREFIXES,
) {

	private val lock = Any()

	private val sessions = CopyOnWriteArraySet<Session>()

	private val logback: LogbackApi? by lazy { LogbackApi.load() }

	@Volatile
	private var installation: Installation? = null

	/**
	 * Open a session that records the WARN and ERROR messages DSS logs on the calling thread
	 * until [Session.stop] is called.
	 *
	 * The first session to open saves the level and additivity of the logger named by
	 * `loggerName`, lowers it to WARN, turns its additivity off and attaches the routing
	 * appender. If the runtime SLF4J binding is not Logback, the session records nothing.
	 */
	fun start(): Session {
		val session = Session(Thread.currentThread())
		synchronized(lock) {
			if (sessions.isEmpty()) logback?.let { api -> runCatching { install(api) } }
			sessions += session
		}
		return session
	}

	/**
	 * Remove [session] from the open sessions, and restore the logger once no session is left
	 * open.
	 *
	 * @return `true` when [session] was open, `false` when it had already been closed.
	 */
	private fun close(session: Session): Boolean {
		synchronized(lock) {
			if (!sessions.remove(session)) return false
			if (sessions.isEmpty()) {
				val current = installation
				val api = logback
				if (current != null && api != null) runCatching { restore(api, current) }
			}
		}
		return true
	}

	/**
	 * Save the state of the logger named by `loggerName` as an [Installation], then attach the
	 * routing appender, turn the logger's additivity off and lower it to WARN.
	 *
	 * The installation is published before the appender is attached, so the appender never sees
	 * an event without it. Additivity goes off only once the appender is attached, so no event is
	 * lost in between.
	 */
	private fun install(api: LogbackApi) {
		val logger = api.logger(loggerName) ?: return
		val context = api.context(logger)
		val current = Installation(
			logger = logger,
			context = context,
			level = api.level(logger),
			effectiveLevel = api.effectiveLevel(logger),
			additive = api.isAdditive(logger),
			parent = api.existing(context, parentName(loggerName) ?: ROOT_LOGGER_NAME),
			appender = api.newAppender(APPENDER_NAME, ::route),
		)
		installation = current
		api.addAppender(current.logger, current.appender)
		api.setAdditive(current.logger, false)
		api.setLevel(current.logger, api.warn)
	}

	/**
	 * Give the logger of [current] back the level and additivity it had before the first session
	 * opened, and detach the routing appender.
	 *
	 * Additivity comes back before the appender is detached, and the appender passes nothing on
	 * while the logger is additive, so no event is lost or delivered twice in between.
	 */
	private fun restore(api: LogbackApi, current: Installation) {
		try {
			api.setLevel(current.logger, current.level)
			api.setAdditive(current.logger, current.additive)
			api.detachAppender(current.logger, current.appender)
		} finally {
			installation = null
		}
	}

	/**
	 * Record [event] in every open session of the thread that logged it, and hand it on to the
	 * appenders above the logger when [forwards] says the log would have shown it without the
	 * capture.
	 *
	 * Any failure is swallowed, because the routing appender runs inside DSS's own logging calls.
	 */
	private fun route(event: Any) {
		val current = installation ?: return
		val api = logback ?: return
		runCatching {
			val thread = Thread.currentThread()
			val owners = sessions.filter { it.thread === thread }
			if (owners.isNotEmpty()) recordable(api, event)?.let { message -> owners.forEach { it.record(message) } }
			if (forwards(api, current, event)) current.parent?.let { api.callAppenders(it, event) }
		}
	}

	/**
	 * Return the message a session records for [event]: its formatted message when the event is at
	 * WARN or above, comes from no logger under [excludedPrefixes] and is not blank, `null` otherwise.
	 */
	private fun recordable(api: LogbackApi, event: Any): String? {
		if (api.levelOf(event) < api.warnInt) return null
		val name = api.loggerNameOf(event)
		if (excludedPrefixes.any { name.startsWith(it) }) return null
		return api.messageOf(event)?.takeIf { it.isNotBlank() }
	}

	/**
	 * Return whether [event] goes on to the appenders above the logger of [current]: the logger
	 * was additive before the capture, is still non-additive, and the logger that emitted the event
	 * would have let it through at the level it had before the capture (see [thresholdOf]).
	 */
	private fun forwards(api: LogbackApi, current: Installation, event: Any): Boolean =
		current.additive && !api.isAdditive(current.logger) &&
			api.levelOf(event) >= thresholdOf(api, current, api.loggerNameOf(event))

	/**
	 * Return the integer value of the level the logger [name] applied before the capture: the
	 * level it, or the nearest logger between it and the captured one, has of its own, which the
	 * capture leaves alone, and otherwise the level the captured logger applied before the first
	 * session opened.
	 */
	private fun thresholdOf(api: LogbackApi, current: Installation, name: String): Int {
		var ancestor: String? = name
		while (ancestor != null && ancestor.length > loggerName.length) {
			api.explicitLevel(current.context, ancestor)?.let { return it }
			ancestor = parentName(ancestor)
		}
		return current.effectiveLevel
	}

	/**
	 * Return the name of the logger directly above the logger [name] in Logback's hierarchy, which
	 * splits names at `.` and `$`, or `null` for a top-level name.
	 */
	private fun parentName(name: String): String? =
		maxOf(name.lastIndexOf('.'), name.lastIndexOf('$')).takeIf { it > 0 }?.let { name.substring(0, it) }

	/**
	 * One operation's capture window, opened by [start] on the thread that runs the operation.
	 *
	 * @property thread The thread whose DSS log events this session records.
	 */
	inner class Session internal constructor(internal val thread: Thread) {

		private val messages = ConcurrentLinkedQueue<String>()

		/**
		 * Add [message], logged on [thread], to what [stop] returns.
		 */
		internal fun record(message: String) {
			messages += message
		}

		/**
		 * Close this session and return the WARN and ERROR messages DSS logged on [thread] while it
		 * was open, in the order they were logged, without duplicates, blank messages or the loggers
		 * under the excluded prefixes.
		 *
		 * Only the first call returns them and later calls return an empty list, so a `finally`
		 * block may call it again after the operation has read its warnings. The last open session
		 * to close restores the level and additivity of the captured logger and detaches the routing
		 * appender.
		 */
		fun stop(): List<String> = if (close(this)) messages.distinct() else emptyList()
	}

	/**
	 * What the first open session saved of the captured logger, and the routing appender it
	 * attached.
	 *
	 * @property logger The Logback logger the routing appender is attached to.
	 * @property context The Logback logger context the logger belongs to.
	 * @property level The level the logger had of its own before the capture, `null` when it
	 *   inherited one.
	 * @property effectiveLevel The integer value of the level the logger applied before the capture.
	 * @property additive Whether the logger passed events on to the loggers above it before the
	 *   capture.
	 * @property parent The logger directly above it, whose appenders, and those above, get the
	 *   events the routing appender hands on.
	 * @property appender The routing appender.
	 */
	private class Installation(
		val logger: Any,
		val context: Any,
		val level: Any?,
		val effectiveLevel: Int,
		val additive: Boolean,
		val parent: Any?,
		val appender: Any,
	)

	/**
	 * The parts of the Logback API this class uses, looked up by reflection.
	 *
	 * @property warn Logback's `Level.WARN`.
	 * @property warnInt The integer value of [warn].
	 */
	private class LogbackApi private constructor(
		private val loggerClass: Class<*>,
		levelClass: Class<*>,
		private val appenderClass: Class<*>,
		contextClass: Class<*>,
		eventClass: Class<*>,
	) {
		private val getLevelMethod = loggerClass.getMethod("getLevel")
		private val getEffectiveLevelMethod = loggerClass.getMethod("getEffectiveLevel")
		private val setLevelMethod = loggerClass.getMethod("setLevel", levelClass)
		private val isAdditiveMethod = loggerClass.getMethod("isAdditive")
		private val setAdditiveMethod = loggerClass.getMethod("setAdditive", Boolean::class.java)
		private val addAppenderMethod = loggerClass.getMethod("addAppender", appenderClass)
		private val detachAppenderMethod = loggerClass.getMethod("detachAppender", appenderClass)
		private val callAppendersMethod = loggerClass.getMethod("callAppenders", eventClass)
		private val getLoggerContextMethod = loggerClass.getMethod("getLoggerContext")
		private val existsMethod = contextClass.getMethod("exists", String::class.java)
		private val toIntMethod = levelClass.getMethod("toInt")
		private val eventLevelMethod = eventClass.getMethod("getLevel")
		private val eventLoggerNameMethod = eventClass.getMethod("getLoggerName")
		private val eventMessageMethod = eventClass.getMethod("getFormattedMessage")

		val warn: Any = levelClass.getField("WARN").get(null)

		val warnInt: Int = toIntMethod.invoke(warn) as Int

		/**
		 * Return the Logback logger named [name], or `null` when SLF4J is not bound to Logback.
		 */
		fun logger(name: String): Any? = LoggerFactory.getLogger(name).takeIf(loggerClass::isInstance)

		/**
		 * Return the level [logger] has of its own, `null` when it inherits one.
		 */
		fun level(logger: Any): Any? = getLevelMethod.invoke(logger)

		/**
		 * Return the integer value of the level [logger] applies.
		 */
		fun effectiveLevel(logger: Any): Int = toIntMethod.invoke(getEffectiveLevelMethod.invoke(logger)) as Int

		/**
		 * Return the integer value of the level the logger [name] of [context] has of its own, or
		 * `null` when it has none or does not exist.
		 */
		fun explicitLevel(context: Any, name: String): Int? =
			existing(context, name)?.let(::level)?.let { toIntMethod.invoke(it) as Int }

		/**
		 * Give [logger] the level [level] of its own, or make it inherit one when [level] is `null`.
		 */
		fun setLevel(logger: Any, level: Any?) {
			setLevelMethod.invoke(logger, level)
		}

		/**
		 * Return whether [logger] passes events on to the loggers above it.
		 */
		fun isAdditive(logger: Any): Boolean = isAdditiveMethod.invoke(logger) as Boolean

		/**
		 * Set whether [logger] passes events on to the loggers above it.
		 */
		fun setAdditive(logger: Any, additive: Boolean) {
			setAdditiveMethod.invoke(logger, additive)
		}

		/**
		 * Attach [appender] to [logger].
		 */
		fun addAppender(logger: Any, appender: Any) {
			addAppenderMethod.invoke(logger, appender)
		}

		/**
		 * Detach [appender] from [logger].
		 */
		fun detachAppender(logger: Any, appender: Any) {
			detachAppenderMethod.invoke(logger, appender)
		}

		/**
		 * Hand [event] to the appenders of [logger] and of the loggers above it, as far as their
		 * additivity reaches.
		 */
		fun callAppenders(logger: Any, event: Any) {
			callAppendersMethod.invoke(logger, event)
		}

		/**
		 * Return the logger context [logger] belongs to.
		 */
		fun context(logger: Any): Any = getLoggerContextMethod.invoke(logger)

		/**
		 * Return the logger [name] of [context] if it exists, without creating it.
		 */
		fun existing(context: Any, name: String): Any? = existsMethod.invoke(context, name)

		/**
		 * Return the integer value of the level of [event].
		 */
		fun levelOf(event: Any): Int = toIntMethod.invoke(eventLevelMethod.invoke(event)) as Int

		/**
		 * Return the name of the logger that emitted [event].
		 */
		fun loggerNameOf(event: Any): String = eventLoggerNameMethod.invoke(event) as? String ?: ""

		/**
		 * Return the message of [event] with its arguments filled in.
		 */
		fun messageOf(event: Any): String? = eventMessageMethod.invoke(event) as? String

		/**
		 * Create an appender named [name] that hands every event it gets to [onEvent].
		 *
		 * It is a dynamic proxy of Logback's `Appender` interface: it reports itself started, equals
		 * only itself, has no filters and ignores the lifecycle, context and status calls.
		 */
		fun newAppender(name: String, onEvent: (Any) -> Unit): Any =
			Proxy.newProxyInstance(appenderClass.classLoader, arrayOf(appenderClass)) { proxy, method, args ->
				when (method.name) {
					"doAppend" -> {
						args?.firstOrNull()?.let(onEvent)
						null
					}
					"getName", "toString" -> name
					"isStarted" -> true
					"equals" -> proxy === args?.firstOrNull()
					"hashCode" -> System.identityHashCode(proxy)
					"getCopyOfAttachedFiltersList" -> emptyList<Any>()
					else -> null
				}
			}

		companion object {
			/**
			 * Look the Logback API up, or return `null` when Logback is not on the classpath or its
			 * API does not match.
			 */
			fun load(): LogbackApi? = runCatching {
				LogbackApi(
					loggerClass = Class.forName("ch.qos.logback.classic.Logger"),
					levelClass = Class.forName("ch.qos.logback.classic.Level"),
					appenderClass = Class.forName("ch.qos.logback.core.Appender"),
					contextClass = Class.forName("ch.qos.logback.classic.LoggerContext"),
					eventClass = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent"),
				)
			}.getOrNull()
		}
	}

	companion object {
		/**
		 * Name the routing appender carries while it is attached.
		 */
		internal const val APPENDER_NAME = "omnisign-dss-capture"

		/**
		 * The logger the routing appender is attached to by default.
		 *
		 * By capturing at the `eu.europa.esig` parent logger, all child loggers that
		 * do not carry their own explicit level inherit WARN during the capture window,
		 * and their events propagate up to the routing appender. This eliminates the need to
		 * enumerate every individual DSS logger that might produce useful warnings.
		 */
		const val DSS_LOGGER_NAME = "eu.europa.esig"

		/**
		 * Logger-name prefixes whose events sessions do not record.
		 *
		 * TSL-related loggers produce extremely verbose output during trusted-list
		 * loading and are handled through a separate TL-warning mechanism in
		 * [DssServiceFactory]. They retain their explicit ERROR level in the logback
		 * configuration, so they normally do not produce WARN events — but this
		 * safeguard ensures any leaked events are not surfaced to the user. Their
		 * ERROR events still reach the configured log outputs.
		 */
		val EXCLUDED_PREFIXES = listOf(
			"eu.europa.esig.dss.tsl.",
		)
	}
}
