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
 * Verifies the limits that keep a multipart request from filling the heap: a request body may exceed
 * `maxFileSize` by at most a megabyte, whether it declares its length or arrives chunked; the form
 * fields, together with the headers of all parts, may take at most that megabyte; and a request may
 * carry at most [MAX_MULTIPART_PARTS] parts. A part whose own `Content-Length` declares more than
 * the limit is read like any other, and a body that ends before its closing boundary is refused as
 * malformed.
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

	test("a body sent chunked is refused with 413 once it passes the request body limit") {
		testApplication {
			application { module(config(SMALL_MAX_FILE_SIZE)) }
			client.post("/api/v1/validate") { setBody(chunked(oversizedPreambleBody())) }
				.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE")
		}
	}

	test("text fields within the request body limit but over the form-field allowance are refused with 413") {
		testApplication {
			application { module(config(LARGE_MAX_FILE_SIZE)) }
			client.post("/api/v1/validate") { setBody(oversizedFields()) }
				.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "FORM_FIELDS_TOO_LARGE")
		}
	}

	test("a part with more header lines than the form-field allowance holds is refused with 413") {
		testApplication {
			application { module(config(LARGE_MAX_FILE_SIZE)) }
			val body = multipartBytes(
				listOf("profile" to "strict"),
				partHeaders = "X-Filler: y\r\n".repeat(FILLER_HEADER_LINES),
			)
			val response = client.post("/api/v1/validate") {
				setBody(ByteArrayContent(body, ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
			}
			response.shouldBeRejectedAs(HttpStatusCode.PayloadTooLarge, "FORM_FIELDS_TOO_LARGE")
		}
	}

	test("a body that ends before its closing boundary is refused with 400 MALFORMED_MULTIPART") {
		testApplication {
			application { module(config(SMALL_MAX_FILE_SIZE)) }
			val body = multipartBytes(listOf("profile" to "strict")).dropLast(CLOSING_BOUNDARY_LINE_BYTES).toByteArray()
			val response = client.post("/api/v1/validate") {
				setBody(ByteArrayContent(body, ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)))
			}
			response.shouldBeRejectedAs(HttpStatusCode.BadRequest, "MALFORMED_MULTIPART")
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
			val body = oversizedPreambleBody()
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

		/**
		 * A `maxFileSize` large enough that the request body limit is far above the form-field
		 * allowance, so the allowance is what a request of large form fields or part headers hits.
		 */
		private const val LARGE_MAX_FILE_SIZE = 8L * 1024 * 1024

		/** A `maxFileSize` that the file part in the part-count tests exceeds. */
		private const val TINY_MAX_FILE_SIZE = 10L

		/** Size of the file part in the part-count tests, above [TINY_MAX_FILE_SIZE]. */
		private const val OVERSIZED_FILE_BYTES = 64

		/** How many large form fields [oversizedFields] and the Netty test send. */
		private const val OVERSIZED_FIELD_COUNT = 3

		/** Characters in each large form field: half a mebibyte, so three exceed the request limit. */
		private const val LARGE_FIELD_CHARS = 512 * 1024

		/**
		 * Characters of the preamble in [oversizedPreambleBody]: two mebibytes, past the request body
		 * limit with [SMALL_MAX_FILE_SIZE]. The server discards a preamble without counting it against
		 * the form-field allowance, so only the body limit stops it.
		 */
		private const val OVERSIZED_PREAMBLE_CHARS = 2 * 1024 * 1024

		/** Header lines of thirteen bytes each in the part-header test: about 1.2 MiB of them. */
		private const val FILLER_HEADER_LINES = 100_000

		/** Boundary of the multipart body the Netty test builds by hand. */
		private const val BOUNDARY = "omnisign-limits-test"

		/** Bytes of the closing boundary line that [multipartBytes] ends with: `--`, [BOUNDARY], `--` and CRLF. */
		private const val CLOSING_BOUNDARY_LINE_BYTES = BOUNDARY.length + 6

		/** Headers of a PDF file part. */
		fun pdfPartHeaders(): Headers = Headers.build {
			append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
			append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
		}

		/**
		 * A multipart body with [BOUNDARY] whose one small form field follows a preamble of
		 * [OVERSIZED_PREAMBLE_CHARS] characters.
		 */
		fun oversizedPreambleBody(): ByteArray =
			multipartBytes(listOf("profile" to "strict"), preamble = "p".repeat(OVERSIZED_PREAMBLE_CHARS))

		/**
		 * The multipart [body] with [BOUNDARY], without its length, so the client sends it chunked and
		 * the server learns its size only by reading it.
		 */
		fun chunked(body: ByteArray): OutgoingContent =
			object : OutgoingContent.WriteChannelContent() {
				override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)

				override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(body)
			}

		/**
		 * A `multipart/form-data` body with [BOUNDARY] that carries [fields] as text parts, each with
		 * [partHeaders], whole CRLF-terminated header lines, after its `Content-Disposition`, and
		 * starts with [preamble].
		 */
		fun multipartBytes(
			fields: List<Pair<String, String>>,
			partHeaders: String = "",
			preamble: String = "",
		): ByteArray = buildString {
			if (preamble.isNotEmpty()) {
				append(preamble)
				append("\r\n")
			}
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
