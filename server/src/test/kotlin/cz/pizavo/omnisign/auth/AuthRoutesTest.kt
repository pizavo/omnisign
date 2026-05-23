package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.HeaderInjectionProviderConfig
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Run [block] with [SESSIONS_DB_FILE_PROPERTY] pointing to a unique temp file, isolating the
 * refresh-token SQLite store from the user's real per-user data directory. Cleans up the
 * file (and SQLite journal sidecar) afterwards even on test failure.
 *
 * @param block Receives the temp DB file so the test can wire a side-channel
 *   [ExposedRefreshTokenStore] for seeding refresh tokens before the route exercises them.
 */
private inline fun <R> withTempSessionsDb(block: (File) -> R): R {
    val tempDb = File.createTempFile("sessions-test-", ".db").also { it.delete() }
    System.setProperty(SESSIONS_DB_FILE_PROPERTY, tempDb.absolutePath)
    try {
        return block(tempDb)
    } finally {
        System.clearProperty(SESSIONS_DB_FILE_PROPERTY)
        tempDb.delete()
        File("${tempDb.absolutePath}-journal").delete()
    }
}

/**
 * Create an [ExposedRefreshTokenStore] pointing at [dbFile] for in-test seeding of
 * refresh tokens. Shares the SQLite file with the application's own store so writes
 * made here are visible to the running app.
 */
private fun testRefreshTokenStore(dbFile: File): ExposedRefreshTokenStore {
    val database = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
    return ExposedRefreshTokenStore(database).also { it.initSchema() }
}

/**
 * Build a [ServerConfig] suitable for `/auth` integration tests: [auth] under test,
 * `operations.allowed` cleared to the empty set so the LOTL background warmup and PKCS#11
 * paths are skipped. Each individual `testApplication` boot drops from ~5s (LOTL XML
 * fetch + parse) to milliseconds. The auth tests do not exercise DSS, and the protected-
 * route tests assert only on the auth gate (`shouldNotBe Unauthorized`), which a `503`
 * from a disabled validate route satisfies equally well.
 */
private fun authTestConfig(auth: AuthConfig?): ServerConfig =
    ServerConfig(
        listen = ListenConfig(host = "127.0.0.1"),
        auth = auth,
        operations = OperationsConfig(allowed = emptySet()),
        cors = CorsConfig(allowedOrigins = listOf("*")),
    )
/**
 * Integration tests for the `/auth` route group, verifying login discovery, JWT
 * session endpoints, and authentication enforcement on protected routes.
 *
 * Tests run in a [testApplication] with [AuthConfig] injected via a custom [ServerConfig]
 * so no real IdP is required.
 */
class AuthRoutesTest : FunSpec({
	
	val jwtSecret = "test-jwt-secret-padded-to-at-least-64-bytes-for-hs512-compatibility!!".sensitive()
	val authConfig = AuthConfig(
		providers = emptyList(),
		session = SessionConfig(
			algorithm = JwtAlgorithmType.HS512,
			issuer = "omnisign",
			audience = "omnisign-api",
			tokenExpirySeconds = 3600,
		),
	)
	fun authTestSecrets(oidcClientSecrets: Map<String, Sensitive<String>> = emptyMap()) =
		ServerSecrets(
			jwtSecret = jwtSecret,
			tlsKeystorePassword = null,
			tlsPrivateKeyPassword = null,
			oidcClientSecrets = oidcClientSecrets,
		)
	val githubClientSecret = "test-client-secret".sensitive()
	
	test("GET /auth/login returns 503 when no providers are configured") {
		testApplication {
			application { module(authTestConfig(authConfig), authTestSecrets()) }
			val response = client.get("/auth/login")
			response.status shouldBe HttpStatusCode.ServiceUnavailable
		}
	}
	
	test("GET /auth/session returns 401 without a token") {
		testApplication {
			application { module(authTestConfig(authConfig), authTestSecrets()) }
			val response = client.get("/auth/session")
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("GET /auth/session returns 401 with a malformed bearer token") {
		testApplication {
			application { module(authTestConfig(authConfig), authTestSecrets()) }
			val response = client.get("/auth/session") {
				bearerAuth("not-a-valid-jwt")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("GET /auth/session returns 200 with a valid JWT") {
		testApplication {
			application { module(authTestConfig(authConfig), authTestSecrets()) }
			
			val jwtService = JwtSessionService(authConfig.session, jwtSecret)
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
			application { module(authTestConfig(authConfig), authTestSecrets()) }
			val response = client.post("/auth/logout")
			response.status shouldBe HttpStatusCode.NoContent
		}
	}

	test("POST /auth/refresh returns 400 when the JSON body is missing") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }
				val response = client.post("/auth/refresh")
				response.status shouldBe HttpStatusCode.BadRequest
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "MISSING_REFRESH_TOKEN"
			}
		}
	}

	test("POST /auth/refresh returns 401 for an unknown refresh token") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }
				val response = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"never-issued-by-this-server"}""")
				}
				response.status shouldBe HttpStatusCode.Unauthorized
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "INVALID_REFRESH_TOKEN"
			}
		}
	}

	test("POST /auth/refresh returns a new access+refresh pair for a valid refresh token") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val principal = AuthenticatedPrincipal(
					userId = "u3",
					email = "refresh@example.com",
					displayName = "Refresh User",
					providerName = "test",
					authTime = Clock.System.now(),
				)
				val refresh = runBlocking { store.issue(principal, 1.days) }

				val response = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				response.status shouldBe HttpStatusCode.OK

				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content shouldNotBe refresh.token
			}
		}
	}

	test("POST /auth/refresh rotates: the consumed refresh token cannot be reused") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val principal = AuthenticatedPrincipal(
					userId = "u-rotate",
					email = "rotate@example.com",
					displayName = null,
					providerName = "test",
					authTime = Clock.System.now(),
				)
				val refresh = runBlocking { store.issue(principal, 1.days) }

				val first = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				first.status shouldBe HttpStatusCode.OK

				val second = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				second.status shouldBe HttpStatusCode.Unauthorized
				val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "INVALID_REFRESH_TOKEN"
			}
		}
	}

	test("POST /auth/refresh rejects a refresh token whose authTime exceeds maxSessionSeconds") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val tooOldAuthTime = Clock.System.now() -
						(authConfig.session.maxSessionSeconds + 60).seconds
				val expiredSessionPrincipal = AuthenticatedPrincipal(
					userId = "u-expired",
					email = "stale@example.com",
					displayName = null,
					providerName = "test",
					authTime = tooOldAuthTime,
				)
				val refresh = runBlocking { store.issue(expiredSessionPrincipal, 1.days) }

				val response = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				response.status shouldBe HttpStatusCode.Unauthorized
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "SESSION_EXPIRED"
			}
		}
	}

	test("POST /auth/refresh preserves the original authTime across the rotated tokens") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val jwtService = JwtSessionService(authConfig.session, jwtSecret)
				val originalAuthTime = Clock.System.now() - 30.minutes
				val principal = AuthenticatedPrincipal(
					userId = "u-stable",
					email = "stable@example.com",
					displayName = null,
					providerName = "test",
					authTime = originalAuthTime,
				)
				val refresh = runBlocking { store.issue(principal, 1.days) }

				val response = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				response.status shouldBe HttpStatusCode.OK

				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				val refreshedAccessToken = body["token"]!!.jsonPrimitive.content
				val refreshedPrincipal = jwtService.verify(refreshedAccessToken)
				refreshedPrincipal shouldNotBe null
				refreshedPrincipal!!.authTime.epochSeconds shouldBe originalAuthTime.epochSeconds
			}
		}
	}

	test("POST /auth/logout deletes the supplied refresh token") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val principal = AuthenticatedPrincipal(
					userId = "u-logout",
					email = "logout@example.com",
					displayName = null,
					providerName = "test",
					authTime = Clock.System.now(),
				)
				val refresh = runBlocking { store.issue(principal, 1.days) }

				val logout = client.post("/auth/logout") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				logout.status shouldBe HttpStatusCode.NoContent

				val refreshAttempt = client.post("/auth/refresh") {
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"${refresh.token}"}""")
				}
				refreshAttempt.status shouldBe HttpStatusCode.Unauthorized
			}
		}
	}

	test("protected API route returns 401 without token when auth.enabled is true") {
		testApplication {
			application { module(authTestConfig(authConfig.copy(enabled = true)), authTestSecrets()) }
			val response = client.post("/api/v1/validate")
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}
	
	test("protected API route is accessible with valid token when auth.enabled is true") {
		testApplication {
			application { module(authTestConfig(authConfig.copy(enabled = true)), authTestSecrets()) }
			
			val jwtService = JwtSessionService(authConfig.session, jwtSecret)
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
			application { module(authTestConfig(authConfig.copy(enabled = true)), authTestSecrets()) }
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
		sharedSecret = headerInjectionSecret.sensitive(),
	)
	val headerInjectionAuthConfig = authConfig.copy(providers = listOf(headerInjectionProvider))

	test("header-injection callback rejects requests without the shared-secret header") {
		testApplication {
			application { module(authTestConfig(headerInjectionAuthConfig), authTestSecrets()) }
			val response = client.get("/auth/callback/shib") {
				header("X-Remote-User", "attacker@evil.com")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	test("header-injection callback rejects requests with a wrong shared-secret value") {
		testApplication {
			application { module(authTestConfig(headerInjectionAuthConfig), authTestSecrets()) }
			val response = client.get("/auth/callback/shib") {
				header("X-Header-Injection-Token", "wrong-secret")
				header("X-Remote-User", "attacker@evil.com")
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	test("header-injection callback issues a token when the shared secret matches") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(headerInjectionAuthConfig), authTestSecrets()) }
				val response = client.get("/auth/callback/shib") {
					header("X-Header-Injection-Token", headerInjectionSecret)
					header("X-Remote-User", "alice@example.com")
					header("X-Shib-Mail", "alice@example.com")
				}
				response.status shouldBe HttpStatusCode.OK
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content.shouldNotBeBlank()
			}
		}
	}

	test("header-injection callback rejects when the user header is missing even with a valid secret") {
		testApplication {
			application { module(authTestConfig(headerInjectionAuthConfig), authTestSecrets()) }
			val response = client.get("/auth/callback/shib") {
				header("X-Header-Injection-Token", headerInjectionSecret)
			}
			response.status shouldBe HttpStatusCode.Unauthorized
		}
	}

	val oauthStateSecret = "oauth-state-secret-padded-to-at-least-64-bytes-for-hmac-nonce-key!".sensitive()
	fun oidcAuthConfig(pkceEnabled: Boolean) = authConfig.copy(
		providers = listOf(
			OidcProviderConfig(
				name = "github",
				preset = SsoProviderPreset.GITHUB,
				clientId = "test-client-id",
				allowedEmailDomains = listOf("*"),
				pkce = pkceEnabled,
			),
		),
		oauthStateSecret = oauthStateSecret,
	)

	test("OIDC /auth/redirect appends code_challenge and code_challenge_method=S256 when PKCE is enabled") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(oidcAuthConfig(pkceEnabled = true)), authTestSecrets(mapOf("github" to githubClientSecret))) }

				val noRedirectClient = createClient {
					followRedirects = false
				}
				val response = noRedirectClient.get("/auth/redirect/github")
				response.status shouldBe HttpStatusCode.Found

				val location = response.headers["Location"]
				location.shouldNotBeNull()
				val locUrl = Url(location)
				val challenge = locUrl.parameters["code_challenge"]
				val method = locUrl.parameters["code_challenge_method"]
				val state = locUrl.parameters["state"]
				challenge.shouldNotBeNull()
				challenge.shouldNotBeBlank()
				method shouldBe "S256"
				state.shouldNotBeNull()
				state.shouldNotBeBlank()

				val verifierStore = ExposedPkceVerifierStore(
					Database.connect(
						"jdbc:sqlite:${tempDb.absolutePath}",
						driver = "org.sqlite.JDBC",
					),
				)
				val storedVerifier = verifierStore.consume(state)
				storedVerifier.shouldNotBeNull()
				val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256")
						.digest(storedVerifier.toByteArray(Charsets.US_ASCII)),
				)
				challenge shouldBe expectedChallenge
			}
		}
	}

	test("OIDC /auth/redirect omits PKCE parameters when provider.pkce is false") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(oidcAuthConfig(pkceEnabled = false)), authTestSecrets(mapOf("github" to githubClientSecret))) }

				val noRedirectClient = createClient {
					followRedirects = false
				}
				val response = noRedirectClient.get("/auth/redirect/github")
				response.status shouldBe HttpStatusCode.Found

				val location = response.headers["Location"]
				location.shouldNotBeNull()
				val locUrl = Url(location)
				locUrl.parameters["code_challenge"].shouldBeNull()
				locUrl.parameters["code_challenge_method"].shouldBeNull()
			}
		}
	}
})




