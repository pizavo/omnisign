package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
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
        operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)),
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
			signingKeystorePassword = null,
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

	/**
	 * Regression test: `/auth/callback/{name}` must live inside the same
	 * `authenticate("oidc-{name}") { }` block as `/auth/redirect/{name}`.
	 *
	 * Ktor attaches the OAuth token-exchange interceptor only to routes enclosed by that
	 * block. A callback mounted outside it never exchanges the authorization code, so
	 * `call.principal<OAuthAccessTokenResponse.OAuth2>()` is always `null` and every OIDC
	 * login dies with `401 OAUTH_FAILED` — the entire authorization-code flow is unreachable
	 * past the IdP redirect.
	 *
	 * Calling the callback with **no** `code` parameter isolates that wiring without needing
	 * a live or mocked IdP: `oauth2HandleCallback()` finds no code, yields
	 * `AuthenticationFailedCause.NoCredentials`, and — because that is not an
	 * `AuthenticationFailedCause.Error` — Ktor issues the authorize challenge instead. So a
	 * `302` to the IdP proves the interceptor ran, whereas the unwired route answers `401`.
	 * The happy-path exchange is covered separately where an IdP can be substituted.
	 */
	test("OIDC /auth/callback is enclosed by its authenticate block so Ktor runs the OAuth interceptor") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(oidcAuthConfig(pkceEnabled = true)), authTestSecrets(mapOf("github" to githubClientSecret))) }

				val noRedirectClient = createClient {
					followRedirects = false
				}
				val response = noRedirectClient.get("/auth/callback/github")

				response.status shouldBe HttpStatusCode.Found
				val location = response.headers["Location"]
				location.shouldNotBeNull()
				location shouldStartWith OidcDiscoveryService.GITHUB_AUTHORIZATION_URL
				Url(location).parameters["state"].shouldNotBeBlank()
			}
		}
	}

	fun handoffAuthConfig(allowedRedirectUris: List<String>) = authConfig.copy(
		providers = listOf(
			OidcProviderConfig(
				name = "github",
				preset = SsoProviderPreset.GITHUB,
				clientId = "test-client-id",
				allowedEmailDomains = listOf("*"),
			),
		),
		oauthStateSecret = oauthStateSecret,
		allowedRedirectUris = allowedRedirectUris,
	)

	test("hand-off: a login carrying returnTo parks it and redirects the browser back with a code") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application {
					module(
						authTestConfig(handoffAuthConfig(listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
					)
				}

				val noRedirect = createClient { followRedirects = false }

				// Redirect leg: the app asks to be returned to an allowlisted page.
				val redirect = noRedirect.get("/auth/redirect/github") {
					url {
						parameters.append("returnTo", "https://app.example.com/")
						parameters.append("handoffChallenge", "challenge-value")
					}
				}
				redirect.status shouldBe HttpStatusCode.Found
				val state = Url(redirect.headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				// The login request was parked under that state.
				val loginStore = ExposedLoginRequestStore(
					Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC"),
				)
				val parked = loginStore.consume(state)
				parked.shouldNotBeNull()
				parked.returnTo shouldBe "https://app.example.com/"
				parked.handoffChallenge shouldBe "challenge-value"
			}
		}
	}

	test("/auth/exchange rejects a malformed body with MISSING_HANDOFF_CODE") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(handoffAuthConfig(listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
					)
				}
				val response = client.post("/auth/exchange") {
					contentType(ContentType.Application.Json)
					setBody("""{"code":"","codeVerifier":""}""")
				}
				response.status shouldBe HttpStatusCode.BadRequest
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "MISSING_HANDOFF_CODE"
			}
		}
	}

	test("/auth/exchange rejects an unknown hand-off code with INVALID_HANDOFF_CODE") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(handoffAuthConfig(listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
					)
				}
				val response = client.post("/auth/exchange") {
					contentType(ContentType.Application.Json)
					setBody("""{"code":"never-issued","codeVerifier":"whatever"}""")
				}
				response.status shouldBe HttpStatusCode.Unauthorized
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "INVALID_HANDOFF_CODE"
			}
		}
	}

	test("/auth/exchange redeems a valid code for a session and sets the refresh cookie") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application {
					module(
						authTestConfig(handoffAuthConfig(listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
					)
				}

				val verifier = "handoff-verifier-string-kept-by-the-client"
				val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
				)
				val database = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC")
				val pkce = PkceService(ExposedPkceVerifierStore(database).also { it.initSchema() })
				val handoffStore = ExposedHandoffCodeStore(database, pkce).also { it.initSchema() }
				val principal = AuthenticatedPrincipal(
					userId = "u-handoff",
					email = "handoff@example.com",
					displayName = "Hand Off",
					providerName = "github",
					authTime = Clock.System.now(),
				)
				val code = runBlocking { handoffStore.issue(principal, challenge, 30.seconds) }

				val response = client.post("/auth/exchange") {
					contentType(ContentType.Application.Json)
					setBody("""{"code":"$code","codeVerifier":"$verifier"}""")
				}
				response.status shouldBe HttpStatusCode.OK
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content.shouldNotBeBlank()

				val setCookie = response.headers["Set-Cookie"]
				setCookie.shouldNotBeNull()
				setCookie shouldContain "omnisign_refresh="
				setCookie shouldContain "HttpOnly"
				setCookie shouldContain "SameSite=Lax"
			}
		}
	}

	test("POST /auth/refresh accepts the refresh token from the cookie when the body omits it") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val principal = AuthenticatedPrincipal(
					userId = "u-cookie",
					email = "cookie@example.com",
					displayName = null,
					providerName = "test",
					authTime = Clock.System.now(),
				)
				val refresh = runBlocking { store.issue(principal, 1.days) }

				val response = client.post("/auth/refresh") {
					header(HttpHeaders.Cookie, "omnisign_refresh=${refresh.token}")
				}
				response.status shouldBe HttpStatusCode.OK
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["refreshToken"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content shouldNotBe refresh.token
			}
		}
	}

	test("POST /auth/logout clears the refresh cookie") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }
				val response = client.post("/auth/logout")
				response.status shouldBe HttpStatusCode.NoContent
				val setCookie = response.headers["Set-Cookie"]
				setCookie.shouldNotBeNull()
				setCookie shouldContain "omnisign_refresh="
				setCookie shouldContain "Max-Age=0"
			}
		}
	}

	test("POST /auth/refresh clears the cookie when the invalid token came from the cookie") {
		withTempSessionsDb {
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }
				val response = client.post("/auth/refresh") {
					header(HttpHeaders.Cookie, "omnisign_refresh=stale-rotated-or-unknown-token")
				}
				response.status shouldBe HttpStatusCode.Unauthorized
				val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "INVALID_REFRESH_TOKEN"
				val setCookie = response.headers["Set-Cookie"]
				setCookie.shouldNotBeNull()
				setCookie shouldContain "omnisign_refresh="
				setCookie shouldContain "Max-Age=0"
			}
		}
	}

	test("POST /auth/refresh does not clear a valid cookie when the invalid token came from the body") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application { module(authTestConfig(authConfig), authTestSecrets()) }

				val store = testRefreshTokenStore(tempDb)
				val principal = AuthenticatedPrincipal(
					userId = "u-body-wins",
					email = "bodywins@example.com",
					displayName = null,
					providerName = "test",
					authTime = Clock.System.now(),
				)
				val valid = runBlocking { store.issue(principal, 1.days) }

				// Body token wins over the cookie; it is garbage, so the refresh fails — but the
				// still-valid cookie token must not be cleared as collateral.
				val rejected = client.post("/auth/refresh") {
					header(HttpHeaders.Cookie, "omnisign_refresh=${valid.token}")
					contentType(ContentType.Application.Json)
					setBody("""{"refreshToken":"garbage-body-token"}""")
				}
				rejected.status shouldBe HttpStatusCode.Unauthorized
				rejected.headers["Set-Cookie"].shouldBeNull()

				// The cookie token still works on its own.
				val accepted = client.post("/auth/refresh") {
					header(HttpHeaders.Cookie, "omnisign_refresh=${valid.token}")
				}
				accepted.status shouldBe HttpStatusCode.OK
			}
		}
	}
})




