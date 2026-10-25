package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.reflect.typeOf

/**
 * Verifies [io.ktor.server.plugins.statuspages.StatusPages] error mapping for domain errors, Ktor's
 * own request exceptions, and generic exceptions.
 */
class StatusPagesTest : FunSpec({

	fun ApplicationTestBuilder.configureTestApp(
		development: Boolean = false,
		handler: suspend RoutingContext.() -> Unit,
	) {
		application {
			configureSerialization()
			configureStatusPages(development = development)
			routing {
				get("/test") { handler() }
			}
		}
	}

	test("OperationException with SigningError.InvalidParameters returns 400") {
		testApplication {
			configureTestApp {
				throw OperationException(
					SigningError.InvalidParameters(text = LocalizableText.Literal("bad input"), details = "detail")
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
					ValidationError.ValidationFailed(text = LocalizableText.Literal("engine error"))
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

	test("BadRequestException returns 400") {
		testApplication {
			configureTestApp {
				throw MissingRequestParameterException("profile")
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.BadRequest
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "BAD_REQUEST"
			body.message shouldBe "Request parameter profile is missing"
		}
	}

	test("NotFoundException returns 404") {
		testApplication {
			configureTestApp {
				throw NotFoundException()
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.NotFound
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "NOT_FOUND"
			body.message shouldBe "Resource not found"
		}
	}

	test("UnsupportedMediaTypeException returns 415") {
		testApplication {
			configureTestApp {
				throw UnsupportedMediaTypeException(ContentType.Text.Plain)
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.UnsupportedMediaType
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "UNSUPPORTED_MEDIA_TYPE"
			body.message shouldBe "Content type text/plain is not supported"
		}
	}

	test("CannotTransformContentToTypeException returns 415") {
		testApplication {
			configureTestApp {
				throw CannotTransformContentToTypeException(typeOf<ApiError>())
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.UnsupportedMediaType
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "UNSUPPORTED_MEDIA_TYPE"
		}
	}

	test("PayloadTooLargeException returns 413") {
		testApplication {
			configureTestApp {
				throw PayloadTooLargeException(1024)
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.PayloadTooLarge
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "PAYLOAD_TOO_LARGE"
			body.message shouldBe "Request is larger than the limit of 1024 bytes"
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

	test("generic exception omits details in non-development mode") {
		testApplication {
			configureTestApp(development = false) {
				throw RuntimeException("internal-detail-should-not-leak")
			}
			val response = client.get("/test")
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.details shouldBe null
		}
	}

	test("generic exception includes details in development mode") {
		testApplication {
			configureTestApp(development = true) {
				throw RuntimeException("dev-mode-debug-detail")
			}
			val response = client.get("/test")
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.details shouldBe "dev-mode-debug-detail"
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

