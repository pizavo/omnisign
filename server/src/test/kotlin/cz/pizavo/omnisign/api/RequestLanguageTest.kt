package cz.pizavo.omnisign.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Tests for [preferredLanguageTag], the reusable `Accept-Language` reader shared by routes.
 */
class RequestLanguageTest : FunSpec({

	suspend fun ApplicationTestBuilder.tagFor(acceptLanguage: String?): String {
		application {
			routing {
				get("/lang") { call.respondText(call.preferredLanguageTag() ?: "NULL") }
			}
		}
		return client.get("/lang") {
			if (acceptLanguage != null) header(HttpHeaders.AcceptLanguage, acceptLanguage)
		}.bodyAsText()
	}

	test("returns null when no Accept-Language header is present") {
		testApplication { tagFor(null) shouldBe "NULL" }
	}

	test("returns the single requested tag") {
		testApplication { tagFor("cs") shouldBe "cs" }
	}

	test("returns the highest-priority tag from a weighted list") {
		testApplication { tagFor("en;q=0.5, cs;q=0.9") shouldBe "cs" }
	}

	test("preserves a region subtag") {
		testApplication { tagFor("cs-CZ") shouldBe "cs-CZ" }
	}

	test("ignores a bare wildcard") {
		testApplication { tagFor("*") shouldBe "NULL" }
	}
})
