package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * Verifies [RemoteConfigArchive] downloads the server's configuration ZIP verbatim and refuses to
 * import one, the web target having no write surface against a provider-authored configuration.
 */
class RemoteConfigArchiveTest : FunSpec({

	test("downloads the archive bytes from api/v1/config/export") {
		val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
		var method: HttpMethod? = null
		var url = ""
		val archive = RemoteConfigArchive(
			mockApiClient { request ->
				method = request.method
				url = request.url.toString()
				respond(content = zip)
			},
		)

		val exported = archive.exportFullConfig().shouldBeRight()

		method shouldBe HttpMethod.Get
		url shouldContain "api/v1/config/export"
		exported.toList() shouldBe zip.toList()
	}

	test("maps a failed download to a configuration error") {
		val archive = RemoteConfigArchive(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.ServiceUnavailable) },
		)

		archive.exportFullConfig().shouldBeLeft()
	}

	test("refuses to import without touching the network") {
		val archive = RemoteConfigArchive(
			mockApiClient { error("import must not issue a request") },
		)

		archive.importFullConfig(byteArrayOf(1, 2, 3)).shouldBeLeft()
	}
})
