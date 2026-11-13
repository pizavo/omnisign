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
import io.ktor.http.content.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies the multipart single-file contract enforced by `MultipartFormReader`: a request with
 * more than one file part is rejected with `400 TOO_MANY_FILES` before any operation runs.
 * A request whose body is not multipart at all is rejected with `415 UNSUPPORTED_MEDIA_TYPE`,
 * one whose multipart `Content-Type` names no boundary with `400 MALFORMED_MULTIPART`, and one
 * whose `Content-Type` cannot be parsed with `400 BAD_REQUEST`.
 */
class MultipartHandlingTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    fun testConfig() = ServerConfig(
        listen = ListenConfig(host = "127.0.0.1"),
        operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)),
        cors = CorsConfig(allowedOrigins = listOf("*")),
    )

    test("validation route rejects a request with two file parts as TOO_MANY_FILES") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", ByteArray(64), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"")
                                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                            })
                            append("attachment", ByteArray(64), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"b.pdf\"")
                                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                            })
                        },
                    ),
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "TOO_MANY_FILES"
        }
    }

    test("validation route rejects two parts sharing the 'file' name as TOO_MANY_FILES") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", ByteArray(64), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"")
                                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                            })
                            append("file", ByteArray(64), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"b.pdf\"")
                                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                            })
                        },
                    ),
                )
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "TOO_MANY_FILES"
        }
    }

    test("validation route answers a body that is not multipart with 415 UNSUPPORTED_MEDIA_TYPE") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate") {
                contentType(ContentType.Application.Json)
                setBody("""{"file":"document.pdf"}""")
            }
            response.status shouldBe HttpStatusCode.UnsupportedMediaType
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "UNSUPPORTED_MEDIA_TYPE"
            body.message shouldBe "Content type application/json is not supported"
        }
    }

    test("validation route answers a request without a Content-Type with 415 UNSUPPORTED_MEDIA_TYPE") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate")
            response.status shouldBe HttpStatusCode.UnsupportedMediaType
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "UNSUPPORTED_MEDIA_TYPE"
            body.message shouldBe "Content-Type header is required"
        }
    }

    test("validation route answers a multipart request without a boundary with 400 MALFORMED_MULTIPART") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate") {
                contentType(ContentType.MultiPart.FormData)
                setBody("--x\r\n\r\n--x--\r\n")
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "MALFORMED_MULTIPART"
        }
    }

    test("validation route answers a Content-Type it cannot parse with 400 BAD_REQUEST") {
        testApplication {
            application { module(testConfig()) }
            val response = client.post("/api/v1/validate") {
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val headers = headersOf(HttpHeaders.ContentType, "multipart/")

                    override fun bytes() = "--x--\r\n".encodeToByteArray()
                })
            }
            response.status shouldBe HttpStatusCode.BadRequest
            val body = json.decodeFromString<ApiError>(response.bodyAsText())
            body.error shouldBe "BAD_REQUEST"
        }
    }
})
