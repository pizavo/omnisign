package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.testing.bodyText
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteArchivingRepository] delegates PAdES extension and timestamp inspection to the
 * server, rebuilding the result from the PDF body plus the `X-OmniSign-Result` metadata header, and
 * refuses the renewal scan that only a filesystem-backed target can perform.
 */
class RemoteArchivingRepositoryTest : FunSpec({

	val meta = """{"newLevel":"PADES_BASELINE_LTA","annotatedWarnings":[]}"""

	test("uploads the document with its target level and rebuilds the extended result") {
		val extended = "%PDF-extended".encodeToByteArray()
		var url = ""
		var body = ""
		val repository = RemoteArchivingRepository(
			mockApiClient { request ->
				url = request.url.toString()
				body = request.body.bodyText()
				respond(content = extended, headers = headersOf("X-OmniSign-Result", meta))
			},
		)

		val result = repository.extendDocument(
			ArchivingParameters(
				inputBytes = "%PDF-1.7".encodeToByteArray(),
				inputName = "signed.pdf",
				targetLevel = SignatureLevel.PADES_BASELINE_LTA,
				profileName = "qualified",
			),
		).shouldBeRight()

		url shouldContain "api/v1/timestamp"
		body shouldContain "filename=\"signed.pdf\""
		body shouldContain "PADES_BASELINE_LTA"
		body shouldContain "qualified"
		result.outputBytes.decodeToString() shouldBe "%PDF-extended"
		result.outputName shouldBe "signed.pdf"
		result.newSignatureLevel shouldBe "PADES_BASELINE_LTA"
	}

	test("fails when the server omits the result metadata header") {
		val repository = RemoteArchivingRepository(mockApiClient { respond(content = byteArrayOf(1)) })

		repository.extendDocument(
			ArchivingParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()
	}

	test("keys a recognized server rejection so the user reads an actionable message") {
		val repository = RemoteArchivingRepository(
			mockApiClient {
				respond(
					content = """{"error":"TIMESTAMP_NOT_ALLOWED","message":"timestamping is disabled here"}""",
					status = HttpStatusCode.Forbidden,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val error = repository.extendDocument(
			ArchivingParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.SERVER_TIMESTAMP_NOT_ALLOWED)
		error.details shouldBe null
	}

	test("falls back to the generic remote message for an unclassified failure") {
		val repository = RemoteArchivingRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.InternalServerError) },
		)

		val error = repository.extendDocument(
			ArchivingParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<ArchivingError.ExtensionFailed>()
		error.text shouldBe ArchivingError.remoteExtensionFailed().text
	}

	test("inspects a document's timestamps through the dedicated route") {
		var url = ""
		val repository = RemoteArchivingRepository(
			mockApiClient { request ->
				url = request.url.toString()
				respond(
					content = Json.encodeToString(
						DocumentTimestampInfo(
							hasDocumentTimestamp = true,
							containsLtData = true,
							hasSignatureTimestamp = true,
						),
					),
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val info = repository.getDocumentTimestampInfo(byteArrayOf(1)).shouldBeRight()

		url shouldContain "api/v1/timestamp/inspect"
		info.hasDocumentTimestamp shouldBe true
		info.containsLtData shouldBe true
		info.hasSignatureTimestamp shouldBe true
	}

	test("maps a failed inspection to an archiving error") {
		val repository = RemoteArchivingRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.BadRequest) },
		)

		repository.getDocumentTimestampInfo(byteArrayOf(1)).shouldBeLeft()
	}

	test("refuses the renewal scan because the web client has no filesystem") {
		val repository = RemoteArchivingRepository(
			mockApiClient { error("the renewal scan must not reach the network") },
		)

		repository.needsArchivalRenewal("/documents/a.pdf", renewalBufferDays = 30).shouldBeLeft()
	}
})
