package cz.pizavo.omnisign.data.repository

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Verifies [DssLogCapture]: what a session records, that sessions on different threads keep their
 * warnings apart and leave the `eu.europa.esig` logger as they found it in whatever order they
 * stop, that they never make it less verbose and keep a level set on it while they are open, and
 * which events still reach the configured log appenders while a session is open.
 *
 * The tests drive the real Logback, which is on the `jvmTest` classpath, watch the root logger's
 * appenders through a [ListAppender], and put back every logger they change after each test.
 */
class DssLogCaptureTest : FunSpec({

	val child = "eu.europa.esig.dss.spi.validation.RevocationDataVerifier"
	val pinnedParent = "eu.europa.esig.dss.pdf"
	val pinnedOuter = "eu.europa.esig.dss.cades.Outer"

	/** Return the Logback logger named [name]. */
	fun logback(name: String): Logger = LoggerFactory.getLogger(name) as Logger

	val dss = logback(DssLogCapture.DSS_LOGGER_NAME)
	val root = logback(Logger.ROOT_LOGGER_NAME)
	val rootLevel = root.level
	val shown = ListAppender<ILoggingEvent>()

	/** Return the messages the root logger's appenders got from DSS loggers since the test started. */
	fun logged(): List<String> =
		shown.list.filter { it.loggerName.startsWith(DssLogCapture.DSS_LOGGER_NAME) }.map { it.formattedMessage }

	/** Run [block] on the single thread of [worker] and return its result. */
	fun <T> on(worker: ExecutorService, block: () -> T): T = worker.submit(Callable { block() }).get()

	beforeTest {
		dss.level = Level.ERROR
		dss.isAdditive = true
		shown.list.clear()
		shown.start()
		root.addAppender(shown)
	}

	afterTest {
		root.detachAppender(shown)
		root.level = rootLevel
		dss.level = null
		dss.isAdditive = true
		logback(pinnedParent).level = null
		logback(pinnedOuter).level = null
	}

	test("stop returns an empty list when nothing was logged") {
		DssLogCapture().start().stop().shouldBeEmpty()
	}

	test("a session returns the WARN and ERROR messages of its thread in order, without duplicates or blank ones") {
		val session = DssLogCapture().start()

		logback(child).debug("debug line")
		logback(child).info("info line")
		logback(child).warn("first warning")
		logback(child).warn("first warning")
		logback(child).error("an error")
		logback(DssLogCapture.DSS_LOGGER_NAME).warn("parent warning")
		logback(child).warn(" ")

		session.stop() shouldContainExactly listOf("first warning", "an error", "parent warning")
	}

	test("a session records the warnings of every DSS logger below the captured one") {
		val session = DssLogCapture().start()

		logback("eu.europa.esig.dss.spi.validation.RevocationDataVerifier").warn("revocation warning")
		logback("eu.europa.esig.dss.service.tsp.OnlineTSPSource").warn("tsp warning")
		logback("eu.europa.esig.dss.spi.CertificateExtensionsUtils").warn("cert warning")

		session.stop() shouldContainExactly listOf("revocation warning", "tsp warning", "cert warning")
	}

	test("excluded prefixes are not recorded, but their errors still reach the log") {
		val session = DssLogCapture().start()

		logback("eu.europa.esig.dss.tsl.sync.SomeTask").warn("tsl noise")
		logback("eu.europa.esig.dss.tsl.sync.SomeTask").error("tsl failure")
		logback("eu.europa.esig.dss.spi.validation.SomeValidator").warn("useful warning")

		session.stop() shouldContainExactly listOf("useful warning")
		logged() shouldContainExactly listOf("tsl failure")
	}

	test("the first session lowers the logger to WARN without additivity, and stopping it restores both and detaches the appender") {
		val session = DssLogCapture().start()

		dss.level shouldBe Level.WARN
		dss.isAdditive shouldBe false
		dss.getAppender(DssLogCapture.APPENDER_NAME).shouldNotBeNull()

		logback(child).warn("warning")
		session.stop() shouldContainExactly listOf("warning")

		dss.level shouldBe Level.ERROR
		dss.isAdditive shouldBe true
		dss.getAppender(DssLogCapture.APPENDER_NAME).shouldBeNull()
		session.stop().shouldBeEmpty()
	}

	test("a logger that had no level of its own gets none back") {
		root.level = Level.ERROR
		dss.level = null

		val session = DssLogCapture().start()
		dss.level shouldBe Level.WARN
		session.stop()

		dss.level.shouldBeNull()
	}

	test("a logger set more verbose than WARN keeps its level, and its debug lines keep reaching the log") {
		dss.level = Level.DEBUG
		val session = DssLogCapture().start()

		dss.level shouldBe Level.DEBUG
		logback(child).debug("debug line")
		logback(child).warn("warning")

		session.stop() shouldContainExactly listOf("warning")
		logged() shouldContainExactly listOf("debug line", "warning")
		dss.level shouldBe Level.DEBUG
		dss.isAdditive shouldBe true
	}

	test("a logger that inherits a level more verbose than WARN gets none of its own") {
		root.level = Level.DEBUG
		dss.level = null
		val session = DssLogCapture().start()

		dss.level.shouldBeNull()
		logback(child).debug("inherited debug line")

		session.stop().shouldBeEmpty()
		logged() shouldContainExactly listOf("inherited debug line")
		dss.level.shouldBeNull()
	}

	test("a level set on the lowered logger while a session is open applies at once and is kept after it stops") {
		val session = DssLogCapture().start()
		dss.level shouldBe Level.WARN

		dss.level = Level.DEBUG
		logback(child).debug("debug after the change")
		logback(child).warn("warning after the change")

		session.stop() shouldContainExactly listOf("warning after the change")
		logged() shouldContainExactly listOf("debug after the change", "warning after the change")
		dss.level shouldBe Level.DEBUG
	}

	test("a level set while a session is open on a logger the capture left alone is kept, WARN included") {
		dss.level = Level.DEBUG
		val session = DssLogCapture().start()

		dss.level = Level.WARN
		logback(child).info("info after the change")
		logback(child).warn("warning after the change")

		session.stop() shouldContainExactly listOf("warning after the change")
		logged() shouldContainExactly listOf("warning after the change")
		dss.level shouldBe Level.WARN
	}

	test("two sessions that overlap without nesting keep their warnings apart and restore the logger once both have stopped") {
		val capture = DssLogCapture()
		Executors.newSingleThreadExecutor().use { first ->
			Executors.newSingleThreadExecutor().use { second ->
				val a = on(first) { capture.start() }
				val b = on(second) { capture.start() }
				on(first) { logback(child).warn("from A") }
				on(second) { logback(child).warn("from B while A is open") }

				val fromA = on(first) { a.stop() }
				dss.level shouldBe Level.WARN
				dss.isAdditive shouldBe false
				on(second) { logback(child).warn("from B after A stopped") }
				val fromB = on(second) { b.stop() }

				fromA shouldContainExactly listOf("from A")
				fromB shouldContainExactly listOf("from B while A is open", "from B after A stopped")
			}
		}

		dss.level shouldBe Level.ERROR
		dss.isAdditive shouldBe true
		dss.getAppender(DssLogCapture.APPENDER_NAME).shouldBeNull()
		logged().shouldBeEmpty()
		logback(child).error("after both")
		logged() shouldContainExactly listOf("after both")
	}

	test("sessions opened on the same thread both record what it logs while they are open") {
		val capture = DssLogCapture()
		val outer = capture.start()
		logback(child).warn("before inner")
		val inner = capture.start()
		logback(child).warn("during both")

		inner.stop() shouldContainExactly listOf("during both")
		dss.level shouldBe Level.WARN
		logback(child).warn("after inner")

		outer.stop() shouldContainExactly listOf("before inner", "during both", "after inner")
		dss.level shouldBe Level.ERROR
	}

	test("a thread with no session records nothing, and its errors reach the log while another thread's session is open") {
		val capture = DssLogCapture()
		Executors.newSingleThreadExecutor().use { first ->
			Executors.newSingleThreadExecutor().use { second ->
				val session = on(first) { capture.start() }
				on(second) {
					logback(child).warn("other warning")
					logback(child).error("other error")
				}

				on(first) { session.stop() }.shouldBeEmpty()
			}
		}

		logged() shouldContainExactly listOf("other error")
	}

	test("an error logged on the session's thread is recorded and reaches the log once") {
		val session = DssLogCapture().start()

		logback(child).error("own error")

		session.stop() shouldContainExactly listOf("own error")
		logged() shouldContainExactly listOf("own error")
	}

	test("a DSS logger with a level of its own, or below one, keeps reaching the log at that level") {
		logback(pinnedParent).level = Level.INFO
		logback(pinnedOuter).level = Level.INFO
		val session = DssLogCapture().start()

		logback("$pinnedParent.PdfParser").info("info under a logger set to INFO")
		logback("$pinnedParent.PdfParser").warn("warning under it")
		logback("$pinnedOuter\$Inner").info("info from an inner class of a logger set to INFO")
		logback(child).warn("warning from an inheriting logger")

		session.stop() shouldContainExactly listOf("warning under it", "warning from an inheriting logger")
		logged() shouldContainExactly listOf(
			"info under a logger set to INFO",
			"warning under it",
			"info from an inner class of a logger set to INFO",
		)
	}

	test("the log gets nothing more when the DSS logger was not additive before the session") {
		dss.isAdditive = false
		val session = DssLogCapture().start()

		logback(child).error("kept inside")

		session.stop() shouldContainExactly listOf("kept inside")
		logged().shouldBeEmpty()
		dss.isAdditive shouldBe false
	}

	test("while the DSS logger is additive during a session, as between restoring it and detaching the appender, an event reaches the log once") {
		val session = DssLogCapture().start()
		dss.isAdditive = true

		logback(child).error("an error while additive")

		logged() shouldContainExactly listOf("an error while additive")
		dss.isAdditive = false
		session.stop()
	}

	test("the routing appender is a started appender without filters that equals only itself") {
		val session = DssLogCapture().start()
		val appender = dss.getAppender(DssLogCapture.APPENDER_NAME).shouldNotBeNull()

		appender.name shouldBe DssLogCapture.APPENDER_NAME
		appender.toString() shouldBe DssLogCapture.APPENDER_NAME
		appender.isStarted shouldBe true
		appender.copyOfAttachedFiltersList.shouldBeEmpty()
		appender shouldBe appender
		appender shouldNotBe shown
		appender.hashCode() shouldBe System.identityHashCode(appender)

		session.stop()
	}
})
