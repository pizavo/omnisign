package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ProxyConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.TlsConfig
import cz.pizavo.omnisign.domain.model.value.sensitive
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
				configureHttpsRedirect(ServerConfig(tls = null, proxy = null))
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

	test("redirect is not installed when reverse-proxy mode is enabled") {
		val tls = TlsConfig(keystorePath = "/tmp/ks.p12", keystorePassword = "pass".sensitive())
		val proxy = ProxyConfig(enabled = true, trusted = listOf("127.0.0.1"))
		testApplication {
			application {
				configureHttpsRedirect(ServerConfig(tls = tls, proxy = proxy))
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

