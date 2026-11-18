package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import eu.europa.esig.dss.service.http.commons.HostConnection
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.http.commons.UserCredentials
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64

/**
 * Sends a timestamp request through DSS's data loader, with HTTP Basic credentials, to a local
 * server that asks for them, while the CLI's logging runs at the levels `--debug-extended` sets,
 * and checks that the credentials reach the server but not the log.
 */
class ExtendedLoggingCredentialsTest : FunSpec({

	test("--debug-extended logs the HTTP client's exchange with a timestamp server but never the credentials it sends") {
		val secret = "tsa-secret"
		val encoded = Base64.encode("tsa-user:$secret".encodeToByteArray())
		val received = AtomicReference<String?>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/tsr") { exchange ->
			exchange.requestBody.readBytes()
			val authorization = exchange.requestHeaders.getFirst("Authorization")
			if (authorization == null) {
				exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"tsa\"")
				exchange.sendResponseHeaders(401, -1)
			} else {
				received.set(authorization)
				val body = byteArrayOf(0x30, 0x00)
				exchange.sendResponseHeaders(200, body.size.toLong())
				exchange.responseBody.use { it.write(body) }
			}
			exchange.close()
		}
		server.start()
		val context = LoggerFactory.getILoggerFactory() as LoggerContext
		val levels = context.loggerList.associateWith { it.level }
		val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
		val appender = ListAppender<ILoggingEvent>().apply {
			this.context = context
			start()
		}
		root.addAppender(appender)
		try {
			applyLogLevels(context, verbose = false, debug = false, extended = true)
			val port = server.address.port
			val loader = TimestampDataLoader().apply {
				addAuthentication(HostConnection("127.0.0.1", port), UserCredentials("tsa-user", secret.toCharArray()))
			}
			loader.post("http://127.0.0.1:$port/tsr", byteArrayOf(1, 2, 3))
		} finally {
			root.detachAppender(appender)
			for ((logger, level) in levels) logger.level = level
			server.stop(0)
		}

		received.get() shouldBe "Basic $encoded"
		appender.list.any { it.loggerName.startsWith("org.apache.hc.client5") && it.level == Level.DEBUG } shouldBe true
		appender.list.map { it.formattedMessage }
			.filter { encoded in it || secret in it || "Authorization: Basic" in it }
			.shouldBeEmpty()
	}
})
