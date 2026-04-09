package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Verifies operation gating via [ServerConfig.allowedOperations] and the capabilities endpoint.
 */
class OperationGatingTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	test("signing route returns 403 when SIGN is not in allowedOperations") {
		testApplication {
			application {
				module(ServerConfig(allowedOperations = setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)))
			}
			val response = client.post("/api/v1/sign")
			response.status shouldBe HttpStatusCode.Forbidden
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "OPERATION_DISABLED"
		}
	}

	test("validation route returns 403 when VALIDATE is not in allowedOperations") {
		testApplication {
			application {
				module(ServerConfig(allowedOperations = setOf(AllowedOperation.TIMESTAMP)))
			}
			val response = client.post("/api/v1/validate")
			response.status shouldBe HttpStatusCode.Forbidden
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "OPERATION_DISABLED"
		}
	}

	test("timestamp route returns 403 when TIMESTAMP is not in allowedOperations") {
		testApplication {
			application {
				module(ServerConfig(allowedOperations = setOf(AllowedOperation.VALIDATE)))
			}
			val response = client.post("/api/v1/timestamp")
			response.status shouldBe HttpStatusCode.Forbidden
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "OPERATION_DISABLED"
		}
	}

	test("capabilities endpoint lists allowed operations and profiles") {
		testApplication {
			application {
				module(ServerConfig(allowedOperations = setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)))
			}
			val response = client.get("/api/v1/capabilities")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
			body.allowedOperations shouldContainExactlyInAnyOrder listOf("VALIDATE", "TIMESTAMP")
			body.authEnabled shouldBe false
		}
	}

	test("capabilities endpoint includes SIGN when enabled") {
		testApplication {
			application {
				module(
					ServerConfig(
						allowedOperations = setOf(
							AllowedOperation.SIGN,
							AllowedOperation.VALIDATE,
							AllowedOperation.TIMESTAMP,
						),
					),
				)
			}
			val response = client.get("/api/v1/capabilities")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
			body.allowedOperations shouldContainExactlyInAnyOrder listOf("SIGN", "VALIDATE", "TIMESTAMP")
		}
	}

	test("capabilities endpoint reports maxFileSize") {
		val customSize = 50L * 1024 * 1024
		testApplication {
			application {
				module(ServerConfig(maxFileSize = customSize))
			}
			val response = client.get("/api/v1/capabilities")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
			body.maxFileSize shouldBe customSize
		}
	}
})

