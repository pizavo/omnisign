package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.validateCorsConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies that [configureCors] installs the hosts [CorsConfig.allowedHosts] reads from `cors.allowedOrigins`, the same
 * ones [validateCorsConfig] judges: an entry written as a URL with its scheme in capitals allows its origin, and the
 * wildcard written as a URL allows any origin, answered with a literal `*` while credentials are off.
 */
class CorsTest : FunSpec({

	/** Answer `GET /test` under the CORS plugin installed from [config], once it has passed [validateCorsConfig]. */
	fun ApplicationTestBuilder.configureTestApp(config: CorsConfig, tlsEnabled: Boolean = false) {
		application {
			configureCors(validateCorsConfig(config), tlsEnabled)
			routing { get("/test") { call.respondText("ok") } }
		}
	}

	test("an entry written as a URL with its scheme in capitals allows its origin with credentials, and no other origin") {
		testApplication {
			configureTestApp(
				CorsConfig(allowedOrigins = listOf("HTTPS://app.example.org"), allowCredentials = true),
				tlsEnabled = true,
			)
			val allowed = client.get("/test") { header(HttpHeaders.Origin, "https://app.example.org") }
			allowed.status shouldBe HttpStatusCode.OK
			allowed.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://app.example.org"
			allowed.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe "true"
			val other = client.get("/test") { header(HttpHeaders.Origin, "https://evil.example") }
			other.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
		}
	}

	test("the wildcard written as a URL allows any origin with a literal star while credentials are off") {
		testApplication {
			configureTestApp(CorsConfig(allowedOrigins = listOf("https://*")))
			val response = client.get("/test") { header(HttpHeaders.Origin, "https://anywhere.example") }
			response.status shouldBe HttpStatusCode.OK
			response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "*"
			response.headers[HttpHeaders.AccessControlAllowCredentials].shouldBeNull()
		}
	}
})
