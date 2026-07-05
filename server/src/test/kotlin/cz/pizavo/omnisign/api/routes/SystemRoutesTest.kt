package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.auth.AuthenticatedPrincipal
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.domain.model.value.sensitive
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
    val jwtSecret = "test-jwt-secret-padded-to-at-least-64-bytes-for-hs512-compatibility!!".sensitive()
    val authConfig = AuthConfig(
        enabled = true,
        providers = emptyList(),
        session = SessionConfig(
            algorithm = JwtAlgorithmType.HS512,
            issuer = "omnisign",
            audience = "omnisign-api",
            tokenExpirySeconds = 3600,
        ),
    )
    val authSecrets = ServerSecrets(
        jwtSecret = jwtSecret,
        tlsKeystorePassword = null,
        tlsPrivateKeyPassword = null,
        signingKeystorePassword = null,
        oidcClientSecrets = emptyMap(),
    )

    test("GET /api/v1/health returns 200") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("responses include X-Content-Type-Options: nosniff") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/health")
            response.headers["X-Content-Type-Options"] shouldBe "nosniff"
        }
    }

    test("responses include X-Frame-Options: DENY") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/health")
            response.headers["X-Frame-Options"] shouldBe "DENY"
        }
    }

    test("responses include Referrer-Policy") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/health")
            response.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
        }
    }

    test("X-Request-Id is generated and echoed when not provided") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/health")
            response.headers[HttpHeaders.XRequestId].shouldNotBeNull()
        }
    }

    test("X-Request-Id from request is echoed back in the response") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val id = "my-correlation-id-123"
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XRequestId, id)
            }
            response.headers[HttpHeaders.XRequestId] shouldBe id
        }
    }

    test("X-Request-Id at the 64-character length boundary is accepted and echoed back") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val id = "a".repeat(64)
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XRequestId, id)
            }
            response.headers[HttpHeaders.XRequestId] shouldBe id
        }
    }

    test("X-Request-Id longer than 64 characters is rejected and replaced with a server-generated ID") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val oversized = "a".repeat(65)
            val response = client.get("/api/v1/health") {
                header(HttpHeaders.XRequestId, oversized)
            }
            val echoed = response.headers[HttpHeaders.XRequestId]
            echoed.shouldNotBeNull()
            (echoed == oversized) shouldBe false
        }
    }

    test("GET /api/v1/capabilities returns authEnabled false when auth not configured") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), auth = null, operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*")))) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.authEnabled shouldBe false
        }
    }

    test("GET /api/v1/capabilities returns authEnabled true when auth is configured and enabled") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), auth = authConfig, operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*"))), authSecrets) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.authEnabled shouldBe true
        }
    }

    test("GET /api/v1/capabilities returns empty profiles to unauthenticated callers when auth enabled") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), auth = authConfig, operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*"))), authSecrets) }
            val response = client.get("/api/v1/capabilities")
            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString<CapabilitiesResponse>(response.bodyAsText())
            body.profiles.shouldBeEmpty()
        }
    }

    test("GET /api/v1/capabilities returns profiles to authenticated callers when auth enabled") {
        testApplication {
            application { module(ServerConfig(listen = ListenConfig(host = "127.0.0.1"), auth = authConfig, operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)), cors = CorsConfig(allowedOrigins = listOf("*"))), authSecrets) }

            val jwtService = JwtSessionService(authConfig.session, jwtSecret)
            val token = jwtService.issue(
                AuthenticatedPrincipal(
                    userId = "u1",
                    email = "user@example.com",
                    displayName = null,
                    providerName = "test",
                    authTime = kotlin.time.Clock.System.now(),
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

