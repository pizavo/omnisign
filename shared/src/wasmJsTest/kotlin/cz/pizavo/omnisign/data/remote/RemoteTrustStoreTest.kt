package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.TrustedCertificateResponse
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteTrustStore] presents the server's trust as a read-only view: [RemoteTrustStore.list]
 * reads the scoped route and maps the sanitized DTOs into domain read models, while every mutating
 * operation fails rather than pretending to write to a provider-authored store.
 */
class RemoteTrustStoreTest : FunSpec({

	val certificate = TrustedCertificateResponse(
		fingerprint = "a1b2c3",
		subjectDN = "CN=OmniSign Free Root CA",
		notBefore = Instant.parse("2025-01-01T00:00:00Z"),
		notAfter = Instant.parse("2035-01-01T00:00:00Z"),
		type = TrustedCertificateType.CA,
	)

	test("is read-only") {
		RemoteTrustStore(mockApiClient { respond(content = "[]") }).readOnly shouldBe true
	}

	test("lists the global scope without a profile query parameter") {
		var url = ""
		var profileParameter: String? = "unset"
		val store = RemoteTrustStore(
			mockApiClient { request ->
				url = request.url.toString()
				profileParameter = request.url.parameters["profile"]
				respond(
					content = Json.encodeToString(listOf(certificate)),
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val certificates = store.list(TrustScope.Global).shouldBeRight()

		url shouldContain "api/v1/config/trusted-certificates"
		profileParameter shouldBe null
		certificates shouldBe listOf(
			TrustedCertificate(
				fingerprint = "a1b2c3",
				subjectDN = "CN=OmniSign Free Root CA",
				notBefore = Instant.parse("2025-01-01T00:00:00Z"),
				notAfter = Instant.parse("2035-01-01T00:00:00Z"),
				type = TrustedCertificateType.CA,
			),
		)
	}

	test("scopes the listing to a profile by query parameter") {
		var profileParameter: String? = null
		val store = RemoteTrustStore(
			mockApiClient { request ->
				profileParameter = request.url.parameters["profile"]
				respond(
					content = "[]",
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		store.list(TrustScope.Profile("qualified")).shouldBeRight() shouldBe emptyList()

		profileParameter shouldBe "qualified"
	}

	test("maps a failed listing to a trust-store error") {
		val store = RemoteTrustStore(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.InternalServerError) },
		)

		store.list(TrustScope.Global).shouldBeLeft()
	}

	test("refuses every mutating operation because the server's trust is provider-authored") {
		val store = RemoteTrustStore(
			mockApiClient { error("no mutating operation may reach the network") },
		)

		store.add(TrustScope.Global, byteArrayOf(1), TrustedCertificateType.CA, source = null).shouldBeLeft()
		store.remove(TrustScope.Global, "a1b2c3").shouldBeLeft()
		store.inspect(byteArrayOf(1)).shouldBeLeft()
		store.setType(TrustScope.Global, "a1b2c3", TrustedCertificateType.TSA).shouldBeLeft()
		store.clearProfileScope("qualified").shouldBeLeft()
		store.resolve(TrustScope.Global).shouldBeLeft()
		store.reference(TrustScope.Global, "a1b2c3", TrustedCertificateType.CA).shouldBeLeft()
		store.findBySource("lotl").shouldBeLeft()
		store.scopes().shouldBeLeft()
	}
})
