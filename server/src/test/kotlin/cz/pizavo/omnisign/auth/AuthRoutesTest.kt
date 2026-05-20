package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.HeaderInjectionProviderConfig
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
/**
 * Integration tests for the `/auth` route group, verifying login discovery, JWT
 * session endpoints, and authentication enforcement on protected routes.
 *
 * Tests run in a [testApplication] with [AuthConfig] injected via a custom [ServerConfig]
 * so no real IdP is required.
 */
class AuthRoutesTest : FunSpec({
	
	val jwtSecret = "test-jwt-secret-padded-to-at-least-64-bytes-for-hs512-compatibility!!"
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
			application { module(ServerConfig(auth = authConfig)) }
			val response = client.get("/auth/login")
			response.status shouldBe HttpStatusCode.ServiceUnavailable
		}
	}
	
	test("GET /auth/session returns 401 without a token") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }
			val response = client.get("/auth/session")
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("GET /auth/session returns 401 with a malformed bearer token") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }
			val response = client.get("/auth/session") {
				bearerAuth("not-a-valid-jwt")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("GET /auth/session returns 200 with a valid JWT") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }
			
			val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
			val principal = AuthenticatedPrincipal(
				userId = "u1",
				email = "user@example.com",
				displayName = "Test User",
				providerName = "test",
				authTime = kotlin.time.Clock.System.now(),
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
			application { module(ServerConfig(auth = authConfig)) }
			val response = client.post("/auth/logout")
			response.status shouldBe HttpStatusCode.NoContent
		}
	}

	test("POST /auth/refresh returns 401 without a token") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }
			val response = client.post("/auth/refresh")
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	test("POST /auth/refresh returns a new token for a valid JWT") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }

			val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
			val principal = AuthenticatedPrincipal(
				userId = "u3",
				email = "refresh@example.com",
				displayName = "Refresh User",
				providerName = "test",
				authTime = kotlin.time.Clock.System.now(),
			)
			val originalToken = jwtService.issue(principal)

			val response = client.post("/auth/refresh") {
				bearerAuth(originalToken)
			}
			response.status shouldBe HttpStatusCode.OK

			val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
		}
	}

	test("POST /auth/refresh rejects a token whose authTime exceeds maxSessionSeconds") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }

			val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
			val tooOldAuthTime = kotlin.time.Clock.System.now() -
					(authConfig.session.maxSessionSeconds + 60).seconds
			val expiredSessionPrincipal = AuthenticatedPrincipal(
				userId = "u-expired",
				email = "stale@example.com",
				displayName = null,
				providerName = "test",
				authTime = tooOldAuthTime,
			)
			val token = jwtService.issue(expiredSessionPrincipal)

			val response = client.post("/auth/refresh") {
				bearerAuth(token)
			}
			response.status shouldBe HttpStatusCode.Unauthorized
			val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["error"]?.jsonPrimitive?.content shouldBe "SESSION_EXPIRED"
		}
	}

	test("POST /auth/refresh preserves the original authTime across the new token") {
		testApplication {
			application { module(ServerConfig(auth = authConfig)) }

			val jwtService = JwtSessionService(authConfig.session.copy(secret = jwtSecret))
			val originalAuthTime = kotlin.time.Clock.System.now() - 30.minutes
			val principal = AuthenticatedPrincipal(
				userId = "u-stable",
				email = "stable@example.com",
				displayName = null,
				providerName = "test",
				authTime = originalAuthTime,
			)
			val originalToken = jwtService.issue(principal)

			val response = client.post("/auth/refresh") {
				bearerAuth(originalToken)
			}
			response.status shouldBe HttpStatusCode.OK

			val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			val refreshedToken = body["token"]!!.jsonPrimitive.content
			val refreshedPrincipal = jwtService.verify(refreshedToken)
			refreshedPrincipal shouldNotBe null
			refreshedPrincipal!!.authTime.epochSeconds shouldBe originalAuthTime.epochSeconds
		}
	}

	test("protected API route returns 401 without token when auth.enabled is true") {
		testApplication {
			application {
				module(
					ServerConfig(
						auth = authConfig.copy(enabled = true),
					),
				)
			}
			val response = client.post("/api/v1/validate")
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("protected API route is accessible with valid token when auth.enabled is true") {
		testApplication {
			application {
				module(
					ServerConfig(
						auth = authConfig.copy(enabled = true),
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
					authTime = kotlin.time.Clock.System.now(),
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
					ServerConfig(
						auth = authConfig.copy(enabled = true),
					),
				)
			}
			val response = client.get("/api/v1/health")
			response.status shouldBe HttpStatusCode.OK
		}
	}

	val headerInjectionSecret = "header-injection-secret-padded-to-at-least-64-bytes-for-test-use!"
	val headerInjectionProvider = HeaderInjectionProviderConfig(
		name = "shib",
		userHeader = "X-Remote-User",
		emailHeader = "X-Shib-Mail",
		displayNameHeader = "X-Shib-Cn",
		sharedSecret = headerInjectionSecret,
	)
	val headerInjectionAuthConfig = authConfig.copy(providers = listOf(headerInjectionProvider))

	test("header-injection callback rejects requests without the shared-secret header") {
		testApplication {
			application { module(ServerConfig(auth = headerInjectionAuthConfig)) }
			val response = client.get("/auth/callback/shib") {
				header("X-Remote-User", "attacker@evil.com")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	test("header-injection callback rejects requests with a wrong shared-secret value") {
		testApplication {
			application { module(ServerConfig(auth = headerInjectionAuthConfig)) }
			val response = client.get("/auth/callback/shib") {
				header("X-Header-Injection-Token", "wrong-secret")
				header("X-Remote-User", "attacker@evil.com")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	test("header-injection callback issues a token when the shared secret matches") {
		testApplication {
			application { module(ServerConfig(auth = headerInjectionAuthConfig)) }
			val response = client.get("/auth/callback/shib") {
				header("X-Header-Injection-Token", headerInjectionSecret)
				header("X-Remote-User", "alice@example.com")
				header("X-Shib-Mail", "alice@example.com")
			}
			response.status shouldBe HttpStatusCode.OK
			val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
		}
	}

	test("header-injection callback rejects when the user header is missing even with a valid secret") {
		testApplication {
			application { module(ServerConfig(auth = headerInjectionAuthConfig)) }
			val response = client.get("/auth/callback/shib") {
				header("X-Header-Injection-Token", headerInjectionSecret)
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
})




