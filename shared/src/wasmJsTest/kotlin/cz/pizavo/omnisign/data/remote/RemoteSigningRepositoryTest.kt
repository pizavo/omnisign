package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteSigningRepository] uploads the document for server-side signing, reconstructs the
 * [cz.pizavo.omnisign.domain.model.result.SigningResult] from the PDF body plus the `X-OmniSign-Result`
 * metadata header, and refuses — without touching the network — the client-side key material
 * operations that only exist for desktop hardware tokens.
 */
class RemoteSigningRepositoryTest : FunSpec({

	val meta = """{"signatureId":"sig-1","signatureLevel":"PADES_BASELINE_T","hasRevocationWarnings":false}"""

	test("uploads the document and its overrides, then rebuilds the result from body and metadata header") {
		val signedPdf = "%PDF-signed".encodeToByteArray()
		var url = ""
		var body = ""
		val repository = RemoteSigningRepository(
			mockApiClient { request ->
				url = request.url.toString()
				body = request.body.bodyText()
				respond(
					content = signedPdf,
					headers = headersOf("X-OmniSign-Result", meta),
				)
			},
		)

		val result = repository.signDocument(
			SigningParameters(
				inputBytes = "%PDF-1.7".encodeToByteArray(),
				inputName = "contract.pdf",
				certificateAlias = "signing-key",
				hashAlgorithm = HashAlgorithm.SHA256,
				signatureLevel = SignatureLevel.PADES_BASELINE_T,
				reason = "Approval",
				location = "Prague",
				contactInfo = "pizavo@gmail.com",
				profileName = "qualified",
			),
		).shouldBeRight()

		url shouldContain "api/v1/sign"
		body shouldContain "filename=\"contract.pdf\""
		body shouldContain "signing-key"
		body shouldContain "SHA256"
		body shouldContain "PADES_BASELINE_T"
		body shouldContain "Approval"
		body shouldContain "Prague"
		body shouldContain "qualified"
		result.outputBytes.decodeToString() shouldBe "%PDF-signed"
		result.outputName shouldBe "contract.pdf"
		result.signatureId shouldBe "sig-1"
		result.signatureLevel shouldBe "PADES_BASELINE_T"
		result.hasRevocationWarnings shouldBe false
	}

	test("sends noTimestamp only when the caller opted out") {
		var body = ""
		val repository = RemoteSigningRepository(
			mockApiClient { request ->
				body = request.body.bodyText()
				respond(content = byteArrayOf(1), headers = headersOf("X-OmniSign-Result", meta))
			},
		)

		repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf", addTimestamp = true),
		).shouldBeRight()
		body shouldNotContain "noTimestamp"

		repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf", addTimestamp = false),
		).shouldBeRight()
		body shouldContain "noTimestamp"
	}

	test("fails when the server omits the result metadata header") {
		val repository = RemoteSigningRepository(
			mockApiClient { respond(content = byteArrayOf(1)) },
		)

		repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()
	}

	test("keys a recognized server rejection so the user reads an actionable message") {
		val repository = RemoteSigningRepository(
			mockApiClient {
				respond(
					content = """{"error":"CERTIFICATE_NOT_ALLOWED","message":"alias 'x' is not permitted"}""",
					status = HttpStatusCode.Forbidden,
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val error = repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<SigningError.SigningFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.SERVER_CERTIFICATE_NOT_ALLOWED)
		error.details shouldBe null
	}

	test("falls back to the generic remote message for an unclassified failure") {
		val repository = RemoteSigningRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.InternalServerError) },
		)

		val error = repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf"),
		).shouldBeLeft()

		error.shouldBeInstanceOf<SigningError.SigningFailed>()
		error.text shouldBe SigningError.remoteSigningFailed().text
	}

	test("lists the server's signing certificates") {
		var url = ""
		val repository = RemoteSigningRepository(
			mockApiClient { request ->
				url = request.url.toString()
				respond(
					content = Json.encodeToString(CertificateDiscoveryResult(certificates = emptyList())),
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val discovered = repository.listAvailableCertificates(promptForLocked = true).shouldBeRight()

		url shouldContain "api/v1/certificates"
		discovered.certificates shouldBe emptyList()
	}

	test("maps a failed certificate listing to a signing error") {
		val repository = RemoteSigningRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.Unauthorized) },
		)

		repository.listAvailableCertificates(promptForLocked = false).shouldBeLeft()
	}

	test("refuses every client-side key material operation without touching the network") {
		val repository = RemoteSigningRepository(
			mockApiClient { error("no client-side key operation may reach the network") },
		)

		repository.signDocument(
			SigningParameters(inputBytes = byteArrayOf(1), inputName = "a.pdf", keystoreFile = "/keys/id.p12"),
		).shouldBeLeft()
		repository.unlockToken("token-1").shouldBeLeft()
		repository.loadCertificatesFromFile("/keys/id.p12").shouldBeLeft()
		repository.listCertificatesFromKeystore("/keys/id.p12", Sensitive("secret")).shouldBeLeft()
	}
})
