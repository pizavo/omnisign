package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
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
 * Verifies that uploads exceeding [ServerConfig.maxFileSize] are rejected with HTTP 413.
 */
class FileSizeEnforcementTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	test("validation route returns 413 when file exceeds maxFileSize") {
		testApplication {
			application {
				module(ServerConfig(maxFileSize = 10L))
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
				module(ServerConfig(maxFileSize = 10L))
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
					maxFileSize = 10L,
					allowedOperations = setOf(AllowedOperation.SIGN, AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP),
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



