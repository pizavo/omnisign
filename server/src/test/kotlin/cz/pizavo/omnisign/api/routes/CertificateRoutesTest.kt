package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Integration tests for the certificate discovery route added by [certificateRoutes].
 */
class CertificateRoutesTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	test("GET /api/v1/certificates returns 403 when SIGN is not in operations.allowed") {
		testApplication {
			application {
				module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)), cors = CorsConfig(allowedOrigins = listOf("*"))))
			}
			val response = client.get("/api/v1/certificates")
			response.status shouldBe HttpStatusCode.Forbidden
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "OPERATION_DISABLED"
		}
	}

	test("GET /api/v1/certificates returns 200 when SIGN is enabled") {
		testApplication {
			application {
				module(
					ServerConfig(
						listen = ListenConfig(host = "127.0.0.1"),
						operations = OperationsConfig(
							allowed = setOf(
								AllowedOperation.SIGN,
								AllowedOperation.VALIDATE,
								AllowedOperation.TIMESTAMP,
							),
						),
						cors = CorsConfig(allowedOrigins = listOf("*")),
					),
				)
			}
			val response = client.get("/api/v1/certificates")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<CertificateDiscoveryResult>(response.bodyAsText())
			body.certificates shouldBe body.certificates
		}
	}

	test("GET /api/v1/certificates filters by operations.certificateAliases") {
		testApplication {
			application {
				module(
					ServerConfig(
						listen = ListenConfig(host = "127.0.0.1"),
						operations = OperationsConfig(
							allowed = setOf(AllowedOperation.SIGN),
							certificateAliases = listOf("allowed-alias"),
						),
						cors = CorsConfig(allowedOrigins = listOf("*")),
					),
				)
			}
			val response = client.get("/api/v1/certificates")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<CertificateDiscoveryResult>(response.bodyAsText())
			body.certificates.forEach { cert ->
				cert.alias shouldBe "allowed-alias"
			}
		}
	}
})

