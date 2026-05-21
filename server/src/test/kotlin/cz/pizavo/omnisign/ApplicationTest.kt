package cz.pizavo.omnisign

import cz.pizavo.omnisign.api.model.responses.HealthResponse
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies the Ktor application module bootstraps correctly and system routes respond.
 */
class ApplicationTest : FunSpec({

	test("health endpoint responds with ok status") {
		testApplication {
			application {
				module(ServerConfig(host = "127.0.0.1", allowedOperations = emptySet(), cors = CorsConfig(allowedOrigins = listOf("*"))))
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
				module(ServerConfig(host = "127.0.0.1", allowedOperations = emptySet(), cors = CorsConfig(allowedOrigins = listOf("*"))))
			}
			val response = client.get("/api/v1/nonexistent")
			response.status shouldBe HttpStatusCode.NotFound
		}
	}
})
