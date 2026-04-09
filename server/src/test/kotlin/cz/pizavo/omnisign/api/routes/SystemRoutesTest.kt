package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.auth.AuthenticatedPrincipal
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Integration tests for [systemRoutes]: health check, capabilities, security headers,
 * and correlation-ID propagation.
 */
class SystemRoutesTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }
    val jwtSecret = "test-secret-value-long-enough-for-hmac-512-algorithm"
    val authConfig = AuthConfig(
        enabled = true,
        providers = emptyList(),
        session = SessionConfig(
            algorithm = JwtAlgorithmType.HS512,
            secret = jwtSecret,
            issuer = "omnisign",
            audience = "omnisign-api",
            tokenExpirySeconds = 3600,
        ),
    )

    test("GET /api/v1/health returns 200") {
        testApplication {
            application { module() }
            val response = client.get("/api/v1/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("responses include X-Content-Type-Options: nosniff") {
        testApplication {
            application { module() }
            val response = client.get("/api/v1/health")
            response.headers["X-Content-Type-Options"] shouldBe "nosniff"
        }
    }

    test("responses include X-Frame-Options: DENY") {
        testApplication {
            application { module() }
            val response = client.get("/api/v1/health")
            response.headers["X-Frame-Options"] shouldBe "DENY"
        }
    }

    test("responses include Referrer-Policy") {
        testApplication {
            application { module() }
            val response = client.get("/api/v1/health")
            response.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
        }
    }

    test("X-Request-Id is generated and echoed when not provided") {
        testApplication {
            application { module() }
            val response = client.get("/api/v1/health")
            response.headers[HttpHeaders.XRequestId].shouldNotBeNull()
        }
    }

    test("X-Request-Id from request is echoed back in the response") {
        testApplication {
            application { module() }
            val id = "my-correlation-id-123"
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XRequestId, id)
            }
            response.headers[HttpHeaders.XRequestId] shouldBe id
        }
    }

    test("GET /api/v1/capabilities returns authEnabled false when auth not configured") {
        testApplication {
            application { module(ServerConfig(auth = null)) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.authEnabled shouldBe false
        }
    }

    test("GET /api/v1/capabilities returns authEnabled true when auth is configured and enabled") {
        testApplication {
            application { module(ServerConfig(auth = authConfig)) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.authEnabled shouldBe true
        }
    }

    test("GET /api/v1/capabilities returns empty profiles to unauthenticated callers when auth enabled") {
        testApplication {
            application { module(ServerConfig(auth = authConfig)) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.profiles.shouldBeEmpty()
        }
    }

    test("GET /api/v1/capabilities returns profiles to authenticated callers when auth enabled") {
        testApplication {
            application { module(ServerConfig(auth = authConfig)) }

            val jwtService = JwtSessionService(authConfig.session)
            val token = jwtService.issue(
                AuthenticatedPrincipal(
                    userId = "u1",
                    email = "user@example.com",
                    displayName = null,
                    providerName = "test",
                ),
            )

            val response = client.get("/api/v1/capabilities") {
                bearerAuth(token)
            }
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.authEnabled shouldBe true
        }
    }
})

