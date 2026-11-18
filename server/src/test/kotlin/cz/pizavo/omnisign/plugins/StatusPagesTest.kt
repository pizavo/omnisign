package cz.pizavo.omnisign.plugins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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

	test("OperationException with SigningError.CertificateRevoked returns 422 CERTIFICATE_REVOKED") {
		testApplication {
			configureTestApp {
				throw OperationException(SigningError.certificateRevoked("Jan Novák", details = "serial number: 42"))
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.UnprocessableEntity
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "CERTIFICATE_REVOKED"
			body.message shouldBe "The certificate issued to Jan Novák has been revoked or suspended, so the document " +
					"was not signed: the signature would not validate. Sign with another certificate"
			body.details shouldBe "serial number: 42"
		}
	}

	test("OperationException with SigningError.LockedDocument returns 422 LOCKED_DOCUMENT") {
		testApplication {
			configureTestApp {
				throw OperationException(SigningError.pdfLocked(details = "Reason : DocMDP dictionary"))
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.UnprocessableEntity
			val body = Json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "LOCKED_DOCUMENT"
			body.message shouldBe "The PDF does not accept further signatures: it was certified with no changes " +
					"allowed, or an earlier signature locked it"
			body.details shouldBe "Reason : DocMDP dictionary"
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

	test("OperationException with ValidationError.InvalidPolicy returns 500 and sends neither the server's policy file nor the reason") {
		val path = "/etc/omnisign/policies/strict-policy.xml"
		val reason = "cvc-elt.1.a: Cannot find the declaration of element 'NotAPolicy'."

		for (error in listOf(
			ValidationError.policyFileNotSet(),
			ValidationError.policyFileUnreadable(path),
			ValidationError.policyFileInvalid(path, reason),
		)) {
			testApplication {
				configureTestApp { throw OperationException(error) }
				val response = client.get("/test")
				response.status shouldBe HttpStatusCode.InternalServerError
				val text = response.bodyAsText()
				text shouldNotContain "strict-policy"
				text shouldNotContain "NotAPolicy"
				val body = Json.decodeFromString<ApiError>(text)
				body.error shouldBe "INVALID_POLICY"
				body.message shouldBe SERVER_POLICY_UNUSABLE
				body.details shouldBe null
			}
		}
	}

	test("OperationException with ValidationError.InvalidPolicy logs the policy file, the reason and the cause as an error") {
		val path = "/etc/omnisign/policies/strict-policy.xml"
		val reason = "cvc-elt.1.a: Cannot find the declaration of element 'NotAPolicy'."
		val cause = IllegalStateException("unmarshalling failed")
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		val plugins = LoggerFactory.getLogger("cz.pizavo.omnisign.plugins") as Logger
		plugins.addAppender(appender)
		try {
			testApplication {
				configureTestApp { throw OperationException(ValidationError.policyFileInvalid(path, reason, cause)) }
				client.get("/test").status shouldBe HttpStatusCode.InternalServerError
			}
		} finally {
			plugins.detachAppender(appender)
		}

		val logged = appender.list.single { it.formattedMessage.startsWith("INVALID_POLICY") }
		logged.level shouldBe Level.ERROR
		logged.formattedMessage shouldContain path
		logged.formattedMessage shouldContain reason
		logged.throwableProxy.shouldNotBeNull().message shouldBe "unmarshalling failed"
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

