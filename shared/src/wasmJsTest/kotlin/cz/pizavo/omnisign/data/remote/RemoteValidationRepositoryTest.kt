package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.testing.bodyText
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteValidationRepository] posts the document and its per-request overrides as the form
 * fields the server's `POST /api/v1/validate` route reads, returns the server's report unchanged, and
 * turns a rejection into a domain error that never leaks the server's own wording.
 */
class RemoteValidationRepositoryTest : FunSpec({

	val report = ValidationReport(
		documentName = "contract.pdf",
		validationTime = Instant.parse("2026-03-01T00:00:00Z"),
		overallResult = ValidationResult.VALID,
		signatures = emptyList(),
	)

	val reportJson = Json.encodeToString(report)

	test("posts the document and its overrides as form fields") {
		var method: HttpMethod? = null
		var url = ""
		var body = ""
		val repository = RemoteValidationRepository(
			mockApiClient { request ->
				method = request.method
				url = request.url.toString()
				body = request.body.bodyText()
				respond(
					content = reportJson,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val result = repository.validateDocument(
			ValidationParameters(
				inputBytes = "%PDF-1.7".encodeToByteArray(),
				inputName = "contract.pdf",
				profileName = "qualified",
				rawReportFormats = setOf(RawReportFormat.XML_DETAILED),
				disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
				disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
			),
		).shouldBeRight()

		method shouldBe HttpMethod.Post
		url shouldContain "api/v1/validate"
		body shouldContain "name=file"
		body shouldContain "filename=\"contract.pdf\""
		body shouldContain "%PDF-1.7"
		body shouldContain "qualified"
		body shouldContain "XML_DETAILED"
		body shouldContain "SHA256"
		body shouldContain "DSA"
		result shouldBe report
	}

	test("omits the optional fields the caller did not set") {
		var body = ""
		val repository = RemoteValidationRepository(
			mockApiClient { request ->
				body = request.body.bodyText()
				respond(
					content = reportJson,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		repository.validateDocument(
			ValidationParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeRight()

		body shouldNotContain "name=profile"
		body shouldNotContain "name=formats"
		body shouldNotContain "name=disableHashAlgorithm"
		body shouldNotContain "name=disableEncryptionAlgorithm"
	}

	test("keys a recognized server rejection so the user reads an actionable message") {
		val repository = RemoteValidationRepository(
			mockApiClient {
				respond(
					content = """{"error":"INVALID_CONFIGURATION","message":"profile 'x' unknown to this server"}""",
					status = HttpStatusCode.BadRequest,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val error = repository.validateDocument(
			ValidationParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<ValidationError.ValidationFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.SERVER_INVALID_CONFIGURATION)
		error.details shouldBe null
	}

	test("falls back to the generic remote message for an unclassified failure") {
		val repository = RemoteValidationRepository(
			mockApiClient {
				respond(
					content = """{"error":"VALIDATION_FAILED","message":"DSS: unable to build certificate chain"}""",
					status = HttpStatusCode.InternalServerError,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val error = repository.validateDocument(
			ValidationParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<ValidationError.ValidationFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.VALIDATION_REMOTE_VALIDATION_FAILED)
		error.details shouldBe null
	}
})
