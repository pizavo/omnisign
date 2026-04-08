package cz.pizavo.omnisign.plugins

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the [configureDefaultHeaders] plugin adds expected response headers.
 */
class DefaultHeadersTest : FunSpec({

	test("X-Powered-By header is present in responses") {
		testApplication {
			application {
				configureDefaultHeaders()
				routing {
					get("/test") {
						call.respondText("ok")
					}
				}
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.OK
			response.headers["X-Powered-By"] shouldBe "OmniSign"
		}
	}
})

