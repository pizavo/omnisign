package cz.pizavo.omnisign.data.remote

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Verifies the shared server-error plumbing every `Remote*Repository` maps its failures through:
 * reading the server's error code off a failed request, keying the codes a user can act on, and
 * the suspending `mapLeft` the mapping needs because reading a response body suspends.
 */
class ServerErrorMappingTest : FunSpec({

	test("serverErrorCode reads the code out of the server's ApiError envelope") {
		val exception = requestFailure(
			HttpStatusCode.BadRequest,
			"""{"error":"INVALID_CONFIGURATION","message":"Profile 'qualified' is not configured"}""",
		)

		exception.serverErrorCode() shouldBe "INVALID_CONFIGURATION"
	}

	test("serverErrorCode tolerates unknown fields in the envelope") {
		val exception = requestFailure(
			HttpStatusCode.Forbidden,
			"""{"error":"TIMESTAMP_NOT_ALLOWED","message":"denied","traceId":"abc","extra":{"nested":1}}""",
		)

		exception.serverErrorCode() shouldBe "TIMESTAMP_NOT_ALLOWED"
	}

	test("serverErrorCode returns null for a transport failure that has no response") {
		RuntimeException("connection refused").serverErrorCode().shouldBeNull()
	}

	test("serverErrorCode returns null when the body is not an ApiError envelope") {
		val exception = requestFailure(HttpStatusCode.BadGateway, "<html>502 Bad Gateway</html>")

		exception.serverErrorCode().shouldBeNull()
	}

	test("serverErrorText keys the three server rejections a user can act on") {
		serverErrorText("INVALID_CONFIGURATION") shouldBe
			LocalizableText.of(MessageKey.SERVER_INVALID_CONFIGURATION)
		serverErrorText("TIMESTAMP_NOT_ALLOWED") shouldBe
			LocalizableText.of(MessageKey.SERVER_TIMESTAMP_NOT_ALLOWED)
		serverErrorText("CERTIFICATE_NOT_ALLOWED") shouldBe
			LocalizableText.of(MessageKey.SERVER_CERTIFICATE_NOT_ALLOWED)
	}

	test("serverErrorText returns null for an unclassified code so the caller falls back to its own message") {
		serverErrorText("SIGNING_FAILED").shouldBeNull()
		serverErrorText("").shouldBeNull()
		serverErrorText(null).shouldBeNull()
	}

	test("mapLeftSuspend transforms a Left through a suspending transform") {
		"boom".left().mapLeftSuspend { it.length } shouldBe 4.left()
	}

	test("mapLeftSuspend passes a Right through untouched") {
		42.right().mapLeftSuspend { "unused" } shouldBe 42.right()
	}
})

/**
 * Issue a request against a mock engine answering [status] with [body], and return the
 * `ResponseException` the `expectSuccess = true` client raises for it — the exact input
 * [serverErrorCode] is handed in production.
 */
private suspend fun requestFailure(status: HttpStatusCode, body: String): Throwable =
	runCatching {
		mockApiClient {
			respond(
				content = body,
				status = status,
				headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
			)
		}.get("https://omnisign.test/api/v1/anything")
	}.exceptionOrNull() ?: error("expected a non-2xx response to raise a ResponseException")
