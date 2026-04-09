package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.TlsConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the [configureHttpsRedirect] plugin behaviour under different configurations.
 */
class HttpsRedirectTest : FunSpec({

	test("redirect is not installed when TLS is null") {
		testApplication {
			application {
				configureHttpsRedirect(ServerConfig(tls = null, proxyMode = false))
				routing {
					get("/test") {
						call.respondText("ok")
					}
				}
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.OK
		}
	}

	test("redirect is not installed when proxyMode is true") {
		val tls = TlsConfig(keystorePath = "/tmp/ks.p12", keystorePassword = "pass")
		testApplication {
			application {
				configureHttpsRedirect(ServerConfig(tls = tls, proxyMode = true))
				routing {
					get("/test") {
						call.respondText("ok")
					}
				}
			}
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.OK
		}
	}
})

