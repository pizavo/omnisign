package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.model.responses.ApiError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * Verifies that [configureRequestBodyLimit] holds request bodies to its limit whether or not they
 * declare a length: a body that declares more is refused before the route reads any of it, a chunked
 * one fails once it passes the limit, and both are answered with `413 PAYLOAD_TOO_LARGE`. A call
 * whose route reads only part of its body still completes, with nothing left reading the body.
 */
class RequestBodyLimitTest : FunSpec({

	fun ApplicationTestBuilder.limitedApp() {
		application {
			configureSerialization()
			configureRequestBodyLimit(LIMIT)
			configureStatusPages()
			routing {
				post("/length") { call.respondText(call.receiveText().length.toString()) }
				post("/unread") {
					call.receiveChannel()
					call.respondText("received")
				}
			}
		}
	}

	suspend fun HttpResponse.shouldBePayloadTooLarge() {
		status shouldBe HttpStatusCode.PayloadTooLarge
		Json.decodeFromString<ApiError>(bodyAsText()).error shouldBe "PAYLOAD_TOO_LARGE"
	}

	test("a body as large as the limit is received whole") {
		testApplication {
			limitedApp()
			val response = client.post("/length") { setBody("a".repeat(LIMIT.toInt())) }
			response.status shouldBe HttpStatusCode.OK
			response.bodyAsText() shouldBe LIMIT.toString()
		}
	}

	test("a body that declares more than the limit is refused before the route reads any of it") {
		testApplication {
			limitedApp()
			client.post("/unread") { setBody("a".repeat(LIMIT.toInt() + 1)) }.shouldBePayloadTooLarge()
		}
	}

	test("a chunked body as large as the limit is received whole") {
		testApplication {
			limitedApp()
			val response = client.post("/length") { setBody(chunked(LIMIT.toInt())) }
			response.status shouldBe HttpStatusCode.OK
			response.bodyAsText() shouldBe LIMIT.toString()
		}
	}

	test("a chunked body larger than the limit fails once the route reads past the limit") {
		testApplication {
			limitedApp()
			client.post("/length") { setBody(chunked(LIMIT.toInt() + 1)) }.shouldBePayloadTooLarge()
		}
	}

	test("on Netty, a call whose route reads only the start of its body completes") {
		val callJob = CompletableDeferred<Job>()
		val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
			configureRequestBodyLimit(PARTLY_READ_LIMIT)
			routing {
				post("/first-byte") {
					callJob.complete(call.coroutineContext.job)
					call.receiveChannel().readByte()
					call.respondText("read one byte")
				}
			}
		}.start(wait = false)
		try {
			val port = server.engine.resolvedConnectors().first().port
			val request = JdkHttpRequest.newBuilder(URI("http://127.0.0.1:$port/first-byte"))
				.version(JdkHttpClient.Version.HTTP_1_1)
				.timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
				.POST(JdkHttpRequest.BodyPublishers.ofByteArray(ByteArray(PARTLY_READ_BODY_BYTES)))
				.build()
			val response = JdkHttpClient.newHttpClient().use { client ->
				client.sendAsync(request, JdkHttpResponse.BodyHandlers.ofString()).await()
			}
			response.statusCode() shouldBe HttpStatusCode.OK.value
			withTimeout(CALL_COMPLETION_TIMEOUT) { callJob.await().join() }
		} finally {
			server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
		}
	}
}) {
	companion object {

		/** The body limit the test application installs, in bytes. */
		private const val LIMIT = 4096L

		/**
		 * A body of [size] bytes that declares no length, so the client sends it chunked, written in
		 * small pieces the way a streaming upload arrives.
		 */
		fun chunked(size: Int): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
			override val contentType = ContentType.Text.Plain

			override suspend fun writeTo(channel: ByteWriteChannel) {
				val piece = ByteArray(PIECE_BYTES) { 'a'.code.toByte() }
				var written = 0
				while (written < size) {
					val length = minOf(PIECE_BYTES, size - written)
					channel.writeFully(piece, 0, length)
					written += length
				}
			}
		}

		/** Size in bytes of each piece [chunked] writes. */
		private const val PIECE_BYTES = 512

		/** The body limit of the server whose route reads only the first byte of its body. */
		private const val PARTLY_READ_LIMIT = 8L * 1024 * 1024

		/**
		 * Size of the body that route receives: within [PARTLY_READ_LIMIT], and larger than the
		 * mebibyte a ktor-io channel buffers before its writer has to wait for a reader.
		 */
		private const val PARTLY_READ_BODY_BYTES = 3 * 1024 * 1024

		/** How long the client waits for the partly read call's response. */
		private const val REQUEST_TIMEOUT_SECONDS = 30L

		/** How long the partly read call may take to complete after its response arrives. */
		private val CALL_COMPLETION_TIMEOUT = 5.seconds
	}
}
