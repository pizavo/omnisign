package cz.pizavo.omnisign.plugins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.pizavo.omnisign.config.ParsedProxyConfig
import cz.pizavo.omnisign.config.RateLimitConfig
import cz.pizavo.omnisign.config.TrustedProxy
import cz.pizavo.omnisign.config.parseTrustedProxy
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.net.Socket

/**
 * Verifies which client address [configureForwardedHeaders] takes from `X-Forwarded-For`, and that the rate limiter
 * keys on it.
 *
 * [forwardedClientAddress] reads the entries of every header line from the right, skips those of trusted proxies and
 * takes the first other one, so the entries a client writes itself, on the left, never count; an entry read on the way
 * that is not an IP address makes the header name no client. [parseForwardedEntry] reads an IPv4 or IPv6 address, with
 * or without a port, and nothing else. On a Netty server reached from 127.0.0.1, the plugin rewrites the call's origin
 * only when that peer is a trusted proxy, so a client that writes a new `X-Forwarded-For` on every request runs out of
 * its quota all the same, every spelling of one address draws on one quota, and two clients behind one trusted proxy
 * each have their own. A trusted proxy's header that names no client is logged at debug level with the proxy's address.
 */
class ForwardedHeadersTest : FunSpec({

	val ok = HttpStatusCode.OK.value
	val tooManyRequests = HttpStatusCode.TooManyRequests.value
	val socketTimeoutMillis = 10_000

	/** Parse each of [entries] as an entry of `proxy.trusted`. */
	fun trusted(entries: List<String>): List<TrustedProxy> =
		entries.map { entry -> requireNotNull(parseTrustedProxy(entry)) { entry } }

	/**
	 * The address [forwardedClientAddress] picks from [headerLines] when [trustedEntries] list the trusted proxies, in
	 * the form the plugin sets it in, or `null` when it picks none.
	 */
	fun clientOf(headerLines: List<String>, vararg trustedEntries: String): String? =
		forwardedClientAddress(headerLines, trusted(trustedEntries.toList()))?.hostAddress

	/**
	 * Run a Netty server on 127.0.0.1 that trusts [trustedEntries] as proxies and allows [authLimit] requests of one
	 * client in the `auth` zone, hand [block] its port, and stop it afterwards. `GET /address` and `GET /host` answer
	 * with the address and the host of the call's origin, and `GET /limited` answers within the `auth` zone.
	 */
	suspend fun withServer(trustedEntries: List<String>, authLimit: Int = 100, block: suspend (port: Int) -> Unit) {
		val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
			configureForwardedHeaders(ParsedProxyConfig(enabled = true, trustedProxies = trusted(trustedEntries)))
			configureRateLimiting(
				RateLimitConfig(auth = RateLimitConfig.Zone(limit = authLimit, refillPeriodSeconds = 3600)),
			)
			routing {
				get("/address") { call.respondText(call.request.origin.remoteAddress) }
				get("/host") { call.respondText(call.request.origin.remoteHost) }
				rateLimit(AUTH_RATE_LIMIT_NAME) { get("/limited") { call.respondText("within the quota") } }
			}
		}.start(wait = false)
		try {
			block(server.engine.resolvedConnectors().first().port)
		} finally {
			server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
		}
	}

	/**
	 * Send `GET [path]` over a new connection to the server on [port], with one `X-Forwarded-For` header line for each
	 * of [forwardedFor], and return the response's status code and body. The request is written by hand, because a
	 * client library may join header lines of one name into one.
	 */
	fun send(port: Int, path: String, vararg forwardedFor: String): Pair<Int, String> =
		Socket("127.0.0.1", port).use { socket ->
			socket.soTimeout = socketTimeoutMillis
			val request = buildString {
				append("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
				for (line in forwardedFor) append("X-Forwarded-For: $line\r\n")
				append("Connection: close\r\n\r\n")
			}
			socket.getOutputStream().write(request.encodeToByteArray())
			val response = socket.getInputStream().readBytes().decodeToString()
			response.substringAfter(' ').substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
		}

	test("the client is the rightmost entry that is not a trusted proxy's, with every trusted proxy on the way skipped") {
		clientOf(listOf("198.51.100.1, 203.0.113.7, 192.0.2.10, 10.1.2.3"), "10.0.0.0/8", "192.0.2.10") shouldBe
			"203.0.113.7"
	}

	test("the entries of every header line count, in the order the lines arrived") {
		clientOf(listOf("198.51.100.1", "203.0.113.7"), "10.0.0.0/8") shouldBe "203.0.113.7"
		clientOf(listOf("203.0.113.7", "10.0.0.2"), "10.0.0.0/8") shouldBe "203.0.113.7"
		clientOf(listOf("198.51.100.1, 203.0.113.7", "10.0.0.2, 10.0.0.3"), "10.0.0.0/8") shouldBe "203.0.113.7"
	}

	test("when every entry is a trusted proxy's, the leftmost is the client's") {
		clientOf(listOf("10.0.0.5, 10.0.0.2"), "10.0.0.0/8") shouldBe "10.0.0.5"
	}

	test("the entries left of the client's are never read, so one a client writes there cannot void the header") {
		clientOf(listOf("not an address, 203.0.113.7, 10.0.0.2"), "10.0.0.0/8") shouldBe "203.0.113.7"
	}

	test("an entry that is not an IP address, read on the way to the client's, makes the header name no client") {
		clientOf(listOf("203.0.113.7, unknown, 10.0.0.2"), "10.0.0.0/8").shouldBeNull()
		clientOf(listOf("203.0.113.7, proxy.internal"), "10.0.0.0/8").shouldBeNull()
	}

	test("empty entries are skipped, and a header without an entry names no client") {
		clientOf(listOf("203.0.113.7,, 10.0.0.2 ,"), "10.0.0.0/8") shouldBe "203.0.113.7"
		clientOf(listOf(""), "10.0.0.0/8").shouldBeNull()
		clientOf(listOf(" , ,"), "10.0.0.0/8").shouldBeNull()
		clientOf(emptyList(), "10.0.0.0/8").shouldBeNull()
	}

	test("parseForwardedEntry reads an IPv4 or IPv6 address, bare or with a port, and drops the port") {
		val addresses = mapOf(
			"203.0.113.7" to "203.0.113.7",
			"203.0.113.7:4711" to "203.0.113.7",
			"2001:db8::7" to "2001:db8:0:0:0:0:0:7",
			"2001:DB8:0::7" to "2001:db8:0:0:0:0:0:7",
			"[2001:db8::7]" to "2001:db8:0:0:0:0:0:7",
			"[2001:db8::7]:443" to "2001:db8:0:0:0:0:0:7",
		)
		for ((entry, address) in addresses) withClue(entry) { parseForwardedEntry(entry)?.hostAddress shouldBe address }
	}

	test("parseForwardedEntry refuses hostnames, zones and every other form that is not an address") {
		val entries = listOf(
			"unknown", "proxy.example", "_hidden", "foo:bar", "203.0.113", "999.0.113.7", "203.0.113.7:",
			"203.0.113.7:port", "fe80::1%eth0", "[fe80::1%eth0]", "[2001:db8::7", "[2001:db8::7]x", "2001:db8::7]:443",
		)
		for (entry in entries) withClue(entry) { parseForwardedEntry(entry).shouldBeNull() }
	}

	test("on Netty, behind a trusted proxy, the call's origin is the client the header names, read from all its lines") {
		withServer(listOf("127.0.0.1")) { port ->
			send(port, "/address", "198.51.100.1, 198.51.100.2", "203.0.113.7") shouldBe (ok to "203.0.113.7")
			send(port, "/host", "198.51.100.1, 198.51.100.2", "203.0.113.7") shouldBe (ok to "203.0.113.7")
			send(port, "/address", "203.0.113.7:4711") shouldBe (ok to "203.0.113.7")
		}
	}

	test("on Netty, the origin stays the peer when the peer is no trusted proxy, or when the header names no client") {
		withServer(listOf("10.0.0.0/8")) { port ->
			send(port, "/address", "203.0.113.7") shouldBe (ok to "127.0.0.1")
		}
		withServer(listOf("127.0.0.1")) { port ->
			send(port, "/address", "unknown") shouldBe (ok to "127.0.0.1")
			send(port, "/address") shouldBe (ok to "127.0.0.1")
		}
	}

	test("on Netty, a header from a trusted proxy that names no client is logged at debug level with the proxy's address") {
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		val plugins = LoggerFactory.getLogger("cz.pizavo.omnisign.plugins") as Logger
		val level = plugins.level
		plugins.level = Level.DEBUG
		plugins.addAppender(appender)
		try {
			withServer(listOf("127.0.0.1")) { port ->
				send(port, "/address", "203.0.113.7, proxy.internal") shouldBe (ok to "127.0.0.1")
			}
		} finally {
			plugins.detachAppender(appender)
			plugins.level = level
		}

		val logged = appender.list.single { "X-Forwarded-For" in it.formattedMessage }
		logged.level shouldBe Level.DEBUG
		logged.formattedMessage shouldContain "'127.0.0.1'"
	}

	test("on Netty, behind a trusted proxy, a client that writes a new entry on every request still runs out of its quota, and another client has its own") {
		withServer(listOf("127.0.0.1"), authLimit = 2) { port ->
			(1..3).map { send(port, "/limited", "198.51.100.$it, 203.0.113.7").first } shouldBe
				listOf(ok, ok, tooManyRequests)
			send(port, "/limited", "198.51.100.9, 203.0.113.8").first shouldBe ok
		}
	}

	test("on Netty, a peer that is no trusted proxy does not reset its quota by sending a new X-Forwarded-For every time") {
		withServer(listOf("10.0.0.0/8"), authLimit = 2) { port ->
			(1..3).map { send(port, "/limited", "203.0.113.$it").first } shouldBe listOf(ok, ok, tooManyRequests)
		}
	}

	test("on Netty, behind a trusted proxy, every spelling of one address draws on one quota") {
		withServer(listOf("127.0.0.1"), authLimit = 2) { port ->
			listOf("2001:db8::7", "2001:DB8:0:0::7", "[2001:db8::7]:443").map { send(port, "/limited", it).first } shouldBe
				listOf(ok, ok, tooManyRequests)
		}
	}
})
