package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.MAX_MULTIPART_PARTS
import cz.pizavo.omnisign.api.requestBodyLimit
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.time.Duration

/**
 * Verifies the limits that keep a multipart request's form fields from filling the heap: a request
 * body may exceed `maxFileSize` by at most a megabyte, whether it declares its length or arrives
 * chunked, and a request may carry at most [MAX_MULTIPART_PARTS] parts. A part whose own
 * `Content-Length` declares more than that is read like any other, instead of failing the request
 * with 500.
 *
 * The last test drives a real Netty server, where a chunked body comes with no length the server
 * could check before reading it.
 */
class MultipartLimitsTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	fun config(maxFileSize: Long) = ServerConfig(
		listen = ListenConfig(host = "127.0.0.1"),
		operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)),
		maxFileSize = maxFileSize,
		cors = CorsConfig(allowedOrigins = listOf("*")),
	)

	/** Form fields that are each well inside the request body limit but together exceed it. */
	fun oversizedFields() = MultiPartFormDataContent(formData {
		repeat(OVERSIZED_FIELD_COUNT) { append("field$it", "b".repeat(LARGE_FIELD_CHARS)) }
	})

	suspend fun HttpResponse.shouldBeRejectedAs(status: HttpStatusCode, error: String) {
		this.status shouldBe status
		json.decodeFromString<ApiError>(bodyAsText()).error shouldBe error
	}

	test("text fields that together exceed the request body limit are refused with 413") {
		testApplication {
			application { module(config(SMALL_MAX_FILE_SIZE)) }
			client.post("/api/v1/validate") { setBody(oversizedFields()) }
				.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
		}
	}

	test("text fields sent chunked are refused with 413 once they pass the request body limit") {
		testApplication {
			application { module(config(SMALL_MAX_FILE_SIZE)) }
			client.post("/api/v1/validate") { setBody(chunked(oversizedFields())) }
				.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
		}
	}

	test("a request with more parts than allowed is refused with 400 TOO_MANY_PARTS") {
		testApplication {
			application { module(config(TINY_MAX_FILE_SIZE)) }
			val response = client.post("/api/v1/validate") {
				setBody(MultiPartFormDataContent(formData {
					repeat(MAX_MULTIPART_PARTS) { append("field$it", "x") }
					append("file", ByteArray(OVERSIZED_FILE_BYTES), pdfPartHeaders())
				}))
			}
			response.shouldBeRejectedAs(HttpStatusCode.BadRequest, "TOO_MANY_PARTS")
		}
	}

	test("a request with as many parts as allowed reaches its last part") {
		testApplication {
			application { module(config(TINY_MAX_FILE_SIZE)) }
			val response = client.post("/api/v1/validate") {
				setBody(MultiPartFormDataContent(formData {
					repeat(MAX_MULTIPART_PARTS - 1) { append("field$it", "x") }
					append("file", ByteArray(OVERSIZED_FILE_BYTES), pdfPartHeaders())
				}))
			}
			response.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE")
		}
	}

	test("a part that declares more than the request body limit in its own Content-Length is read") {
		testApplication {
			application { module(config(SMALL_MAX_FILE_SIZE)) }
			val body = multipartBytes(
				listOf("profile" to "strict"),
				partHeaders = "Content-Length: ${requestBodyLimit(SMALL_MAX_FILE_SIZE) + 1}\r\n",
			)
			val response = client.post("/api/v1/validate") {
				setBody(ByteArrayContent(body, ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
			}
			response.shouldBeRejectedAs(HttpStatusCode.BadRequest, "MISSING_FILE")
		}
	}

	test("on Netty, a chunked multipart body over the request body limit is refused with 413") {
		val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
			module(config(SMALL_MAX_FILE_SIZE))
		}.start(wait = false)
		try {
			val port = server.engine.resolvedConnectors().first().port
			val body = multipartBytes(List(OVERSIZED_FIELD_COUNT) { "field$it" to "b".repeat(LARGE_FIELD_CHARS) })
			val request = JdkHttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/validate"))
				.version(JdkHttpClient.Version.HTTP_1_1)
				.timeout(Duration.ofSeconds(30))
				.header(HttpHeaders.ContentType, "multipart/form-data; boundary=$BOUNDARY")
				.POST(JdkHttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body) })
				.build()
			val response = JdkHttpClient.newHttpClient().use { client ->
				client.sendAsync(request, JdkHttpResponse.BodyHandlers.ofString()).await()
			}
			response.statusCode() shouldBe HttpStatusCode.PayloadTooLarge.value
			json.decodeFromString<ApiError>(response.body()).error shouldBe "PAYLOAD_TOO_LARGE"
		} finally {
			server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
		}
	}
}) {
	companion object {

		/** A `maxFileSize` small enough that the request body limit is about one mebibyte. */
		private const val SMALL_MAX_FILE_SIZE = 1024L

		/** A `maxFileSize` that the file part in the part-count tests exceeds. */
		private const val TINY_MAX_FILE_SIZE = 10L

		/** Size of the file part in the part-count tests, above [TINY_MAX_FILE_SIZE]. */
		private const val OVERSIZED_FILE_BYTES = 64

		/** How many large form fields [oversizedFields] and the Netty test send. */
		private const val OVERSIZED_FIELD_COUNT = 3

		/** Characters in each large form field: half a mebibyte, so three exceed the request limit. */
		private const val LARGE_FIELD_CHARS = 512 * 1024

		/** Boundary of the multipart body the Netty test builds by hand. */
		private const val BOUNDARY = "omnisign-limits-test"

		/** Headers of a PDF file part. */
		fun pdfPartHeaders(): Headers = Headers.build {
			append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
			append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
		}

		/**
		 * [content] without its length, so the client sends it chunked and the server learns its size
		 * only by reading it.
		 */
		fun chunked(content: MultiPartFormDataContent): OutgoingContent =
			object : OutgoingContent.WriteChannelContent() {
				override val contentType = content.contentType

				override suspend fun writeTo(channel: ByteWriteChannel) = content.writeTo(channel)
			}

		/**
		 * A `multipart/form-data` body with [BOUNDARY] that carries [fields] as text parts, each with
		 * [partHeaders], whole CRLF-terminated header lines, after its `Content-Disposition`.
		 */
		fun multipartBytes(fields: List<Pair<String, String>>, partHeaders: String = ""): ByteArray = buildString {
			fields.forEach { (name, value) ->
				append("--$BOUNDARY\r\n")
				append("Content-Disposition: form-data; name=\"$name\"\r\n")
				append(partHeaders)
				append("\r\n")
				append(value)
				append("\r\n")
			}
			append("--$BOUNDARY--\r\n")
		}.toByteArray()
	}
}
