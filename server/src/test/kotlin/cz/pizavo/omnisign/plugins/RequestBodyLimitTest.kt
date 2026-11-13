package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.model.responses.ApiError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

/**
 * Verifies that [configureRequestBodyLimit] holds request bodies to its limit whether or not they
 * declare a length: a body that declares more is refused before the route reads any of it, a chunked
 * one fails once it passes the limit, and both are answered with `413 PAYLOAD_TOO_LARGE`.
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
	}
}
