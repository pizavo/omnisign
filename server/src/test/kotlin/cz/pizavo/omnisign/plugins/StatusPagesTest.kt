package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies [io.ktor.server.plugins.statuspages.StatusPages] error mapping for domain errors and generic exceptions.
 */
class StatusPagesTest : FunSpec({

	fun ApplicationTestBuilder.configureTestApp(handler: suspend RoutingContext.() -> Unit) {
		application {
			configureSerialization()
			configureStatusPages()
			routing {
				get("/test") { handler() }
			}
		}
	}

	test("OperationException with SigningError.InvalidParameters returns 400") {
		testApplication {
			configureTestApp {
				throw OperationException(
					SigningError.InvalidParameters(message = "bad input", details = "detail")
				)
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.BadRequest
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "INVALID_PARAMETERS"
			body.message shouldBe "bad input"
			body.details shouldBe "detail"
		}
	}

	test("OperationException with ValidationError.ValidationFailed returns 500") {
		testApplication {
			configureTestApp {
				throw OperationException(
					ValidationError.ValidationFailed(message = "engine error")
				)
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.InternalServerError
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "VALIDATION_FAILED"
		}
	}

	test("IllegalArgumentException returns 400") {
		testApplication {
			configureTestApp {
				throw IllegalArgumentException("oops")
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.BadRequest
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "BAD_REQUEST"
		}
	}

	test("generic exception returns 500") {
		testApplication {
			configureTestApp {
				throw RuntimeException("unexpected")
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.InternalServerError
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "INTERNAL_ERROR"
		}
	}

	test("429 TooManyRequests status returns RATE_LIMIT_EXCEEDED JSON body") {
		testApplication {
			application {
				configureSerialization()
				configureStatusPages()
				routing {
					get("/test") {
						call.respond(HttpStatusCode.TooManyRequests)
					}
				}
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.TooManyRequests
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "RATE_LIMIT_EXCEEDED"
		}
	}
})

