package cz.pizavo.omnisign.plugins

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the [configureAutoHeadResponse] plugin auto-responds to HEAD requests.
 */
class AutoHeadResponseTest : FunSpec({

	test("HEAD request to a GET route returns 200 with no body") {
		testApplication {
			application {
				configureAutoHeadResponse()
				routing {
					get("/test") {
						call.respondText("payload")
					}
				}
			}
			val response = client.head("/test")
			response.status shouldBe HttpStatusCode.OK
		}
	}
})

