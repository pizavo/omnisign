package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.legal.ThirdPartyComponent
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
 * Verifies [RemoteServerCreditsRepository] fetches the connected server's third-party credits from
 * the documented route and hands back the deserialized response, including the licence and source
 * fields that carry the deployment's offer of source.
 */
class RemoteServerCreditsRepositoryTest : FunSpec({

	test("gets the credits from api/v1/credits") {
		var method: HttpMethod? = null
		var url = ""
		val repository = RemoteServerCreditsRepository(
			mockApiClient { request ->
				method = request.method
				url = request.url.toString()
				respond(
					content = Json.encodeToString(
						CreditsResponse(
							components = listOf(
								ThirdPartyComponent(
									name = "EU DSS (Digital Signature Services)",
									licenseId = "LGPL-2.1-or-later",
									licenseName = "GNU Lesser General Public License v2.1 or later",
									licenseText = "LGPL-2.1.txt",
									copyright = "Copyright 2015 European Commission",
									homepage = "https://github.com/esig/dss",
									surfaces = listOf("server"),
									artifacts = 40,
								),
							),
						),
					),
					headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
				)
			},
		)

		val credits = repository.get()

		method shouldBe HttpMethod.Get
		url shouldContain "api/v1/credits"
		credits.components.single().name shouldBe "EU DSS (Digital Signature Services)"
		credits.components.single().licenseId shouldBe "LGPL-2.1-or-later"
		credits.license shouldBe "AGPL-3.0-or-later"
		credits.source shouldBe "https://github.com/pizavo/omnisign"
		credits.poweredBy shouldBe "OmniSign"
	}

	test("propagates a 404 so an older server degrades rather than showing a wrong list") {
		val repository = RemoteServerCreditsRepository(
			mockApiClient { respond(content = "not found", status = HttpStatusCode.NotFound) },
		)

		shouldThrowAny { repository.get() }
	}

	test("propagates a server failure because the contract has no error channel") {
		val repository = RemoteServerCreditsRepository(
			mockApiClient { respond(content = "nope", status = HttpStatusCode.InternalServerError) },
		)

		shouldThrowAny { repository.get() }
	}
})
