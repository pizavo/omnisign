package cz.pizavo.omnisign

import cz.pizavo.omnisign.api.model.responses.HealthResponse
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.TlsConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies the Ktor application module bootstraps correctly and system routes respond, and that it
 * refuses to start with a `tls` block whose protocol versions the server cannot offer.
 */
class ApplicationTest : FunSpec({

	test("health endpoint responds with ok status") {
		testApplication {
			application {
				module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*"))))
			}
			val response = client.get("/api/v1/health")
			response.status shouldBe HttpStatusCode.OK

			val body = Json.decodeFromString<HealthResponse>(response.bodyAsText())
			body.status shouldBe "ok"
		}
	}

	test("unknown endpoint returns 404") {
		testApplication {
			application {
				module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*"))))
			}
			val response = client.get("/api/v1/nonexistent")
			response.status shouldBe HttpStatusCode.NotFound
		}
	}

	test("the module refuses to start when tls.enabledProtocols lists no version or one the server does not offer") {
		val secrets = ServerSecrets(
			jwtSecret = null,
			tlsKeystorePassword = Sensitive("unused"),
			tlsPrivateKeyPassword = null,
			signingKeystorePassword = null,
			oidcClientSecrets = emptyMap(),
		)
		for (protocols in listOf(emptyList<String>(), listOf("TLSv1.1"))) {
			val config = ServerConfig(
				listen = ListenConfig(host = "127.0.0.1"),
				cors = CorsConfig(allowedOrigins = listOf("*")),
				tls = TlsConfig(keystorePath = "unused.p12", enabledProtocols = protocols),
			)
			val ex = shouldThrow<IllegalArgumentException> {
				testApplication {
					application { module(config, secrets) }
					startApplication()
				}
			}
			ex.message!! shouldContain "tls.enabledProtocols"
		}
	}
})
