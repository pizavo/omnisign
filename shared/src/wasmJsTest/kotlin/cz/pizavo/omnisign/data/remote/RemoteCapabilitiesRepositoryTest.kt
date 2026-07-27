package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteCapabilitiesRepository] fetches the server's capability advertisement from the
 * documented route and hands back the deserialized response, including the branding fields the web
 * client composes its title from.
 */
class RemoteCapabilitiesRepositoryTest : FunSpec({

	test("gets the capabilities from api/v1/capabilities") {
		var method: HttpMethod? = null
		var url = ""
		val repository = RemoteCapabilitiesRepository(
			mockApiClient { request ->
				method = request.method
				url = request.url.toString()
				respond(
					content = Json.encodeToString(
						CapabilitiesResponse(
							allowedOperations = listOf("VALIDATE", "SIGN"),
							profiles = listOf("qualified"),
							maxFileSize = 20_971_520,
							authEnabled = true,
							organizationName = "Acme",
						),
					),
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val capabilities = repository.get()

		method shouldBe HttpMethod.Get
		url shouldContain "api/v1/capabilities"
		capabilities.allowedOperations shouldBe listOf("VALIDATE", "SIGN")
		capabilities.profiles shouldBe listOf("qualified")
		capabilities.maxFileSize shouldBe 20_971_520
		capabilities.authEnabled shouldBe true
		capabilities.organizationName shouldBe "Acme"
	}

	test("defaults organizationName to null when the operator set no branding") {
		val repository = RemoteCapabilitiesRepository(
			mockApiClient {
				respond(
					content = """{"allowedOperations":["VALIDATE"],"profiles":[],"maxFileSize":1024,"authEnabled":false}""",
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		repository.get().organizationName shouldBe null
	}

	test("propagates a server failure because the contract has no error channel") {
		val repository = RemoteCapabilitiesRepository(
			mockApiClient { respond(content = "nope", status = HttpStatusCode.InternalServerError) },
		)

		shouldThrowAny { repository.get() }
	}
})
