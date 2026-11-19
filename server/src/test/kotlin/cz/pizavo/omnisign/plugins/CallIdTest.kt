package cz.pizavo.omnisign.plugins

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies which client-supplied `X-Request-Id` values [configureCallId] accepts: one of 1 to 64 characters, each in
 * Ktor's default dictionary `[a-z0-9+/=-]`. Any other value gives way to a server-generated V7 UUID, which the response
 * echoes and the route sees as the call's ID.
 */
class CallIdTest : FunSpec({

	/** Answer `GET /test` with the call's ID, under the plugin [configureCallId] installs. */
	fun ApplicationTestBuilder.configureTestApp() {
		application {
			configureCallId()
			routing { get("/test") { call.respondText(call.callId ?: "") } }
		}
	}

	val generatedId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

	test("an ID made only of dictionary characters is accepted, echoed and seen by the route") {
		testApplication {
			configureTestApp()
			val id = "req-1+a/b=c"
			val response = client.get("/test") { header(HttpHeaders.XRequestId, id) }
			response.headers[HttpHeaders.XRequestId] shouldBe id
			response.bodyAsText() shouldBe id
		}
	}

	test("IDs of 1 and of 64 dictionary characters are accepted") {
		testApplication {
			configureTestApp()
			for (id in listOf("a", "0".repeat(64))) {
				val response = client.get("/test") { header(HttpHeaders.XRequestId, id) }
				withClue(id) { response.headers[HttpHeaders.XRequestId] shouldBe id }
			}
		}
	}

	test("an empty ID and one of 65 dictionary characters are replaced by a generated V7 UUID") {
		testApplication {
			configureTestApp()
			for (id in listOf("", "0".repeat(65))) {
				val response = client.get("/test") { header(HttpHeaders.XRequestId, id) }
				withClue(id) { response.headers[HttpHeaders.XRequestId]!! shouldMatch generatedId }
			}
		}
	}

	test("an ID with a character outside the dictionary is replaced by a generated V7 UUID") {
		val ids = listOf("x | 200 OK | POST /api/v1/sign", "Req-1", "req_1", "req.1", "req:1", "req;1", "\"req\"")
		testApplication {
			configureTestApp()
			for (id in ids) {
				val response = client.get("/test") { header(HttpHeaders.XRequestId, id) }
				val echoed = response.headers[HttpHeaders.XRequestId]
				withClue(id) {
					echoed shouldNotBe id
					echoed!! shouldMatch generatedId
					response.bodyAsText() shouldBe echoed
				}
			}
		}
	}

	test("a request without an ID gets a generated V7 UUID") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.headers[HttpHeaders.XRequestId]!! shouldMatch generatedId
		}
	}
})
