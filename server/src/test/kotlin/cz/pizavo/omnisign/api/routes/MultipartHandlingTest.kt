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
 * Verifies the multipart single-file contract enforced by `collectParts`: a request with
 * more than one file part is rejected with `400 TOO_MANY_FILES` before any operation runs.
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
})
