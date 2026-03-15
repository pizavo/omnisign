package cz.pizavo.omnisign

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.request.get
import io.ktor.server.testing.*

/**
 * Verifies the Ktor application module bootstraps correctly.
 */
class ApplicationTest : FunSpec({
	
	test("root endpoint responds") {
		testApplication {
			application {
				module()
			}
			val response = client.get("/")
			response.shouldNotBeNull()
		}
	}
})

