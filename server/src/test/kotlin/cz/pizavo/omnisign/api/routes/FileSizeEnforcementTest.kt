package cz.pizavo.omnisign.api.routes

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
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies that uploads exceeding [ServerConfig.maxFileSize] are rejected with HTTP 413, and
 * that [ServerConfig.maxFileSize] rather than Ktor's own multipart part limit decides which
 * uploads are accepted.
 */
class FileSizeEnforcementTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	val ktorPartLimitProperty = "io.ktor.server.request.formFieldLimit"

	/**
	 * Run [block] with Ktor's default multipart part limit lowered to [bytes], standing in for
	 * its 50 MiB default without uploading that much.
	 */
	suspend fun withKtorDefaultPartLimit(bytes: Long, block: suspend () -> Unit) {
		System.setProperty(ktorPartLimitProperty, bytes.toString())
		try {
			block()
		} finally {
			System.clearProperty(ktorPartLimitProperty)
		}
	}

	/**
	 * Headers of a PDF file part named [fileName].
	 */
	fun pdfPartHeaders(fileName: String): Headers = Headers.build {
		append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
		append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
	}

	test("a file above Ktor's default part limit but within maxFileSize gets past the multipart parser") {
		withKtorDefaultPartLimit(1024) {
			testApplication {
				application {
					module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), maxFileSize = 64L * 1024, cors = CorsConfig(allowedOrigins = listOf("*"))))
				}
				val response = client.post("/api/v1/validate") {
					setBody(MultiPartFormDataContent(formData {
						append("file", ByteArray(8 * 1024), pdfPartHeaders("first.pdf"))
						append("second", ByteArray(8 * 1024), pdfPartHeaders("second.pdf"))
					}))
				}
				response.status shouldBe HttpStatusCode.BadRequest
				json.decodeFromString<ApiError>(response.bodyAsText()).error shouldBe "TOO_MANY_FILES"
			}
		}
	}

	test("a file above maxFileSize is answered with 413 when maxFileSize exceeds Ktor's default part limit") {
		withKtorDefaultPartLimit(1024) {
			testApplication {
				application {
					module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), maxFileSize = 4L * 1024, cors = CorsConfig(allowedOrigins = listOf("*"))))
				}
				val response = client.post("/api/v1/validate") {
					setBody(MultiPartFormDataContent(formData {
						append("file", ByteArray(8 * 1024), pdfPartHeaders("big.pdf"))
					}))
				}
				response.status shouldBe HttpStatusCode.PayloadTooLarge
				json.decodeFromString<ApiError>(response.bodyAsText()).error shouldBe "FILE_TOO_LARGE"
			}
		}
	}

	test("validation route returns 413 when file exceeds maxFileSize") {
		testApplication {
			application {
				module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), maxFileSize = 10L, cors = CorsConfig(allowedOrigins = listOf("*"))))
			}
			val response = client.post("/api/v1/validate") {
				setBody(MultiPartFormDataContent(formData {
					append("file", ByteArray(100), Headers.build {
						append(HttpHeaders.ContentDisposition, "filename=\"big.pdf\"")
						append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
					})
				}))
			}
			response.status shouldBe HttpStatusCode.PayloadTooLarge
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "FILE_TOO_LARGE"
		}
	}

	test("timestamp route returns 413 when file exceeds maxFileSize") {
		testApplication {
			application {
				module(ServerConfig(
					listen = ListenConfig(host = "127.0.0.1"),
					maxFileSize = 10L,
					operations = OperationsConfig(allowed = setOf(AllowedOperation.TIMESTAMP)),
					cors = CorsConfig(allowedOrigins = listOf("*")),
				))
			}
			val response = client.post("/api/v1/timestamp") {
				setBody(MultiPartFormDataContent(formData {
					append("file", ByteArray(100), Headers.build {
						append(HttpHeaders.ContentDisposition, "filename=\"big.pdf\"")
						append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
					})
				}))
			}
			response.status shouldBe HttpStatusCode.PayloadTooLarge
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "FILE_TOO_LARGE"
		}
	}

	test("signing route returns 413 when file exceeds maxFileSize") {
		testApplication {
			application {
				module(ServerConfig(
					listen = ListenConfig(host = "127.0.0.1"),
					maxFileSize = 10L,
					operations = OperationsConfig(allowed = setOf(AllowedOperation.SIGN, AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)),
					cors = CorsConfig(allowedOrigins = listOf("*")),
				))
			}
			val response = client.post("/api/v1/sign") {
				setBody(MultiPartFormDataContent(formData {
					append("file", ByteArray(100), Headers.build {
						append(HttpHeaders.ContentDisposition, "filename=\"big.pdf\"")
						append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
					})
				}))
			}
			response.status shouldBe HttpStatusCode.PayloadTooLarge
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "FILE_TOO_LARGE"
		}
	}
})



