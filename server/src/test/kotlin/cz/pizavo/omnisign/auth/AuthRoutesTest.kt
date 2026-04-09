package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integration tests for the `/auth` route group, verifying login discovery, JWT
 * session endpoints, and authentication enforcement on protected routes.
 *
 * Tests run in a [testApplication] with [AuthConfig] injected via a custom [ServerConfig]
 * so no real IdP is required.
 */
class AuthRoutesTest : FunSpec({

    val jwtSecret = "test-secret-value-long-enough-for-hmac-256"
    val authConfig = AuthConfig(
        providers = emptyList(),
        session = SessionConfig(
            algorithm = JwtAlgorithmType.HS512,
            secret = jwtSecret,
            issuer = "omnisign",
            audience = "omnisign-api",
            tokenExpirySeconds = 3600,
        ),
    )

    test("GET /auth/login returns 503 when no providers are configured") {
        testApplication {
            application { module(cz.pizavo.omnisign.config.ServerConfig(auth = authConfig)) }
            val response = client.get("/auth/login")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }

    test("GET /auth/session returns 401 without a token") {
        testApplication {
            application { module(cz.pizavo.omnisign.config.ServerConfig(auth = authConfig)) }
            val response = client.get("/auth/session")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("GET /auth/session returns 401 with a malformed bearer token") {
        testApplication {
            application { module(cz.pizavo.omnisign.config.ServerConfig(auth = authConfig)) }
            val response = client.get("/auth/session") {
                bearerAuth("not-a-valid-jwt")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("GET /auth/session returns 200 with a valid JWT") {
        testApplication {
            application { module(cz.pizavo.omnisign.config.ServerConfig(auth = authConfig)) }

            val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
            val principal = AuthenticatedPrincipal(
                userId = "u1",
                email = "user@example.com",
                displayName = "Test User",
                providerName = "test",
            )
            val token = jwtService.issue(principal)

            val response = client.get("/auth/session") {
                bearerAuth(token)
            }
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["email"]?.jsonPrimitive?.content shouldBe "user@example.com"
            body["providerName"]?.jsonPrimitive?.content shouldBe "test"
        }
    }

    test("POST /auth/logout always returns 204") {
        testApplication {
            application { module(cz.pizavo.omnisign.config.ServerConfig(auth = authConfig)) }
            val response = client.post("/auth/logout")
            response.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("protected API route returns 401 without token when requireLogin is true") {
        testApplication {
            application {
                module(
                    cz.pizavo.omnisign.config.ServerConfig(
                        requireLogin = true,
                        auth = authConfig,
                    ),
                )
            }
            val response = client.post("/api/v1/validate")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("protected API route is accessible with valid token when requireLogin is true") {
        testApplication {
            application {
                module(
                    cz.pizavo.omnisign.config.ServerConfig(
                        requireLogin = true,
                        auth = authConfig,
                    ),
                )
            }

            val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
            val token = jwtService.issue(
                AuthenticatedPrincipal(
                    userId = "u2",
                    email = "admin@example.com",
                    displayName = null,
                    providerName = "test",
                ),
            )

            val response = client.post("/api/v1/validate") {
                bearerAuth(token)
            }
            response.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }

    test("health endpoint is always accessible without authentication") {
        testApplication {
            application {
                module(
                    cz.pizavo.omnisign.config.ServerConfig(
                        requireLogin = true,
                        auth = authConfig,
                    ),
                )
            }
            val response = client.get("/api/v1/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }
})




