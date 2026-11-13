package cz.pizavo.omnisign.data.repository

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the limits [AiaDataLoader] holds every download to, against an HTTP server on the
 * loopback interface: the schemes it fetches, the internal addresses it refuses unless allowed, the
 * body size it accepts, and the time it may take.
 *
 * The bodies that never end would hang a loader that drained them on close, so those tests wait for
 * the download on a thread of its own, for a limited time; see [within].
 */
class AiaDataLoaderTest : FunSpec({

	val issuer = ByteArray(ISSUER_BYTES) { it.toByte() }
	val issuerRequests = AtomicInteger()
	val handlers = Executors.newCachedThreadPool()
	val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		executor = handlers
		createContext("/issuer.cer") { exchange ->
			issuerRequests.incrementAndGet()
			exchange.sendResponseHeaders(200, issuer.size.toLong())
			exchange.responseBody.use { it.write(issuer) }
		}
		createContext("/declared-large") { exchange ->
			exchange.sendResponseHeaders(200, LARGE_BYTES.toLong())
			streamUntilClosed(exchange) { it.write(ByteArray(LARGE_BYTES)) }
		}
		createContext("/endless") { exchange ->
			exchange.sendResponseHeaders(200, 0)
			streamUntilClosed(exchange) { out ->
				while (true) {
					out.write(ByteArray(CHUNK_BYTES))
					Thread.sleep(CHUNK_INTERVAL_MILLIS)
				}
			}
		}
		createContext("/drip") { exchange ->
			exchange.sendResponseHeaders(200, 0)
			streamUntilClosed(exchange) { out ->
				while (true) {
					out.write(1)
					out.flush()
					Thread.sleep(DRIP_INTERVAL_MILLIS)
				}
			}
		}
		start()
	}
	val base = "http://127.0.0.1:${server.address.port}"

	afterSpec {
		server.stop(0)
		handlers.shutdownNow()
	}

	fun loader(allowPrivateAddresses: Boolean = true, timeoutMillis: Int = TIMEOUT_MILLIS) =
		AiaDataLoader(timeoutMillis, allowPrivateAddresses, maxResponseBytes = MAX_BYTES)

	test("an issuer is downloaded over http") {
		loader().get("$base/issuer.cer") shouldBe issuer
	}

	test("an internal address is refused before any request is sent when private addresses are not allowed") {
		val before = issuerRequests.get()

		val error = shouldThrow<DSSExternalResourceException> {
			loader(allowPrivateAddresses = false).get("$base/issuer.cer")
		}

		error.message shouldContain "no public address"
		issuerRequests.get() shouldBe before
	}

	test("a file URL is refused without being read") {
		val file = tempfile().apply { writeBytes(issuer) }

		val error = shouldThrow<DSSExternalResourceException> { loader().get(file.toURI().toString()) }

		error.message shouldContain "only over http and https"
	}

	test("ftp and ldap URLs are refused") {
		shouldThrow<DSSExternalResourceException> { loader().get("ftp://127.0.0.1/issuer.cer") }
		shouldThrow<DSSExternalResourceException> { loader().get("ldap://127.0.0.1/cn=Issuer?cACertificate;binary") }
	}

	test("a list of URLs skips the refused ones and downloads the first that answers") {
		val file = tempfile().apply { writeBytes(issuer) }

		val answer = loader().get(listOf(file.toURI().toString(), "$base/issuer.cer"))

		answer.urlString shouldBe "$base/issuer.cer"
		answer.data shouldBe issuer
	}

	test("a body declared larger than the cap is refused before it is read") {
		val error = shouldThrow<DSSExternalResourceException> {
			within(WAIT_LIMIT) { loader().get("$base/declared-large") }
		}

		error.message shouldContain "larger than $MAX_BYTES bytes"
	}

	test("a body that never ends is cut off at the cap") {
		val error = shouldThrow<DSSExternalResourceException> {
			within(WAIT_LIMIT) { loader().get("$base/endless") }
		}

		error.message shouldContain "larger than $MAX_BYTES bytes"
	}

	test("a server that sends a byte at a time is cut off when the download takes too long") {
		val error = shouldThrow<DSSExternalResourceException> {
			within(WAIT_LIMIT) { loader(timeoutMillis = DRIP_TIMEOUT_MILLIS).get("$base/drip") }
		}

		error.message shouldContain "took longer than $DRIP_TIMEOUT_MILLIS ms"
	}

	test("nothing can be posted") {
		shouldThrow<UnsupportedOperationException> { loader().post("$base/issuer.cer", byteArrayOf(1)) }
	}
}) {
	companion object {

		/** Size of the issuer the server hands out. */
		private const val ISSUER_BYTES = 1_500

		/** Body cap of the loaders under test, small enough for the tests to cross it quickly. */
		private const val MAX_BYTES = 16 * 1024

		/** Body size the `/declared-large` endpoint announces and sends. */
		private const val LARGE_BYTES = 4 * MAX_BYTES

		/** Size of each write of the `/endless` endpoint. */
		private const val CHUNK_BYTES = 4 * 1024

		/**
		 * Pause between the writes of the `/endless` endpoint. A working loader reaches the cap after a
		 * few writes; a broken one reads on until the test gives up, and the pause keeps what it reads
		 * meanwhile, which HttpClient logs at debug level, to a few megabytes.
		 */
		private const val CHUNK_INTERVAL_MILLIS = 20L

		/** Time limit of the loaders under test, unless a test sets its own. */
		private const val TIMEOUT_MILLIS = 5_000

		/** Time limit for the `/drip` test, well under the time the server would keep sending. */
		private const val DRIP_TIMEOUT_MILLIS = 1_000

		/** Pause between the bytes of the `/drip` endpoint, shorter than any read timeout under test. */
		private const val DRIP_INTERVAL_MILLIS = 100L

		/** How long a test waits for a download that a working loader ends within about a second. */
		private val WAIT_LIMIT = 15.seconds

		/**
		 * Write the body of [exchange] with [write] until the client goes away, which surfaces as an
		 * [IOException] from the stream, or the server stops, then close the exchange.
		 */
		private fun streamUntilClosed(exchange: HttpExchange, write: (java.io.OutputStream) -> Unit) {
			try {
				exchange.responseBody.use(write)
			} catch (_: IOException) {
			} catch (_: InterruptedException) {
			} finally {
				exchange.close()
			}
		}

		/**
		 * Run [download] on a thread of its own and wait for it at most [limit], passing on what it
		 * returns or throws.
		 *
		 * A download stuck reading a socket ignores both interruption and Kotest's test timeout, so a
		 * test that called a broken loader directly would hang the whole run. Through this it fails
		 * with a [TimeoutException] instead, and the stuck daemon thread ends when the server stops.
		 */
		private fun <T> within(limit: Duration, download: () -> T): T =
			try {
				CompletableFuture.supplyAsync(download).get(limit.inWholeMilliseconds, TimeUnit.MILLISECONDS)
			} catch (e: ExecutionException) {
				throw e.cause ?: e
			}
	}
}
