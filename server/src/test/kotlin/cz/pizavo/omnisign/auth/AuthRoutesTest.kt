package cz.pizavo.omnisign.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
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
 * A [MockEngine] standing in for the GitHub IdP: it answers the OAuth2 token exchange with a canned
 * bearer token (form-encoded, as GitHub does), the user API with [userInfoJson], and the account's
 * email list with [emailsJson]. Substituted for the CIO engine of the server's outbound
 * [io.ktor.client.HttpClient] via the [module] `httpClientEngine` seam, so the callback's
 * token-exchange -> UserInfo hop runs end-to-end with no live IdP. GitHub is deliberate: its
 * hard-coded endpoints skip OIDC discovery and its non-OIDC nature skips id_token verification, so
 * the mock only serves these three endpoints.
 *
 * @param userInfoJson The user API's answer.
 * @param tokenRequests Receives the form parameters of each token-exchange POST, so a spec can see
 *   what a real IdP would have been sent.
 * @param emailsJson The email list's answer.
 * @param emailsStatus The email list's status, e.g. `404` as GitHub answers without the
 *   `user:email` scope.
 * @param requestedUrls Receives the URL of every request the server makes.
 */
private fun githubIdpMockEngine(
    userInfoJson: String,
    tokenRequests: MutableList<Parameters> = mutableListOf(),
    emailsJson: String = "[]",
    emailsStatus: HttpStatusCode = HttpStatusCode.OK,
    requestedUrls: MutableList<String> = mutableListOf(),
): MockEngine =
    MockEngine { request ->
        val url = request.url.toString()
        requestedUrls += url
        when {
            url.startsWith(OidcDiscoveryService.GITHUB_TOKEN_URL) -> {
                tokenRequests += parseQueryString(request.body.toByteArray().decodeToString())
                respond(
                    "access_token=gh-access-token&token_type=bearer",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()),
                )
            }
            url == OidcUserInfoService.GITHUB_USER_EMAILS_API_URL ->
                respond(
                    emailsJson,
                    emailsStatus,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            url.startsWith(OidcDiscoveryService.GITHUB_USER_API_URL) ->
                respond(
                    userInfoJson,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            else -> respond("unexpected IdP request: $url", HttpStatusCode.InternalServerError)
        }
    }

/** UserInfo JSON for a GitHub user in the `example.com` email domain. */
private const val OCTOCAT_USER_INFO =
    """{"id":12345,"login":"octocat","email":"octocat@example.com","name":"The Octocat"}"""

/**
 * The GitHub email list of [OCTOCAT_USER_INFO]'s account, marking its public address [verified].
 *
 * @param verified The `verified` flag of `octocat@example.com`.
 */
private fun octocatEmails(verified: Boolean) =
    """[{"email":"octocat@example.com","verified":$verified,"primary":true,"visibility":"public"}]"""

/** Where [oidcIdpMockEngine]'s IdP publishes its discovery document. */
private const val TEST_IDP_DISCOVERY_URL = "https://idp.example/.well-known/openid-configuration"

/**
 * A [MockEngine] standing in for a standard OIDC IdP: it serves the discovery document at
 * [discoveryUrl], answers the token exchange with a canned bearer token and [idToken], and
 * UserInfo with [userInfoJson]. The IdP's signing keys are fetched outside the server's HTTP
 * client, so a spec that has the id_token verified serves them with [withJwksServer] and passes
 * that server's [jwksUri].
 *
 * @param userInfoJson UserInfo's answer.
 * @param idToken The id_token the token exchange returns, or `null` for none.
 * @param discoveryUrl Where the discovery document is served.
 * @param issuer The `issuer` the discovery document declares.
 * @param jwksUri The `jwks_uri` the discovery document declares.
 */
private fun oidcIdpMockEngine(
    userInfoJson: String,
    idToken: String? = null,
    discoveryUrl: String = TEST_IDP_DISCOVERY_URL,
    issuer: String = "https://idp.example",
    jwksUri: String = "https://idp.example/jwks",
): MockEngine =
    MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when (request.url.toString()) {
            discoveryUrl -> respond(
                """
                {"issuer":"$issuer","authorization_endpoint":"https://idp.example/authorize",
                 "token_endpoint":"https://idp.example/token","userinfo_endpoint":"https://idp.example/userinfo",
                 "jwks_uri":"$jwksUri"}
                """.trimIndent(),
                HttpStatusCode.OK,
                json,
            )
            "https://idp.example/token" -> respond(
                if (idToken == null) {
                    """{"access_token":"idp-access-token","token_type":"Bearer"}"""
                } else {
                    """{"access_token":"idp-access-token","token_type":"Bearer","id_token":"$idToken"}"""
                },
                HttpStatusCode.OK,
                json,
            )
            "https://idp.example/userinfo" -> respond(userInfoJson, HttpStatusCode.OK, json)
            else -> respond("unexpected IdP request: ${request.url}", HttpStatusCode.InternalServerError)
        }
    }

/** The `kid` under which [withJwksServer] publishes the test signing key. */
private const val TEST_SIGNING_KID = "test-signing-key"

/** The RSA key pair the test IdP signs its id_tokens with. */
private val testSigningKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/**
 * Sign an id_token for the client `test-client-id` with [testSigningKeys], valid for a minute.
 *
 * @param issuer The `iss` claim, which must equal the discovery document's issuer.
 * @param claims Further claims: `email`, `email_verified`, `xms_edov` and the like.
 */
private fun signTestIdToken(issuer: String, claims: Map<String, Any>): String {
    val builder = JWT.create()
        .withKeyId(TEST_SIGNING_KID)
        .withIssuer(issuer)
        .withAudience("test-client-id")
        .withSubject("u1")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
    claims.forEach { (name, value) ->
        when (value) {
            is Boolean -> builder.withClaim(name, value)
            is String -> builder.withClaim(name, value)
            else -> error("unsupported test claim type for '$name'")
        }
    }
    return builder.sign(
        Algorithm.RSA256(testSigningKeys.public as RSAPublicKey, testSigningKeys.private as RSAPrivateKey),
    )
}

/**
 * Serve [testSigningKeys]' public key as a JWKS on a loopback port for the duration of [block],
 * since the server fetches signing keys through `URLConnection` rather than its mockable HTTP
 * client.
 *
 * @param keyIssuer The issuer the key's entry names, as Entra ID's do, or `null` for none.
 * @param block Receives the JWKS URL.
 */
private fun <R> withJwksServer(keyIssuer: String? = null, block: (jwksUri: String) -> R): R {
    val publicKey = testSigningKeys.public as RSAPublicKey
    fun base64Url(value: BigInteger): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    val issuerMember = if (keyIssuer == null) "" else ",\"issuer\":\"$keyIssuer\""
    val jwks = """{"keys":[{"kty":"RSA","kid":"$TEST_SIGNING_KID","use":"sig","alg":"RS256",""" +
        """"n":"${base64Url(publicKey.modulus)}","e":"${base64Url(publicKey.publicExponent)}"$issuerMember}]}"""
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/jwks") { exchange ->
        val bytes = jwks.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        return block("http://127.0.0.1:${server.address.port}/jwks")
    } finally {
        server.stop(0)
    }
}

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
	 * Regression test: within one call, Ktor looks the OAuth settings up for its OAuth1a branch and
	 * again for its OAuth2 branch, and the verifier is single-use. When every lookup took it, the
	 * OAuth1a branch spent it and the token exchange went out with no `code_verifier`, which an IdP
	 * enforcing PKCE refuses. Only the outgoing POST shows this: the authorize URL and the verifier
	 * store, which the specs above check, were right all along.
	 */
	test("OIDC callback sends the token endpoint the verifier behind the authorize URL's code_challenge") {
		withTempSessionsDb {
			testApplication {
				val tokenRequests = mutableListOf<Parameters>()
				application {
					module(
						authTestConfig(oidcAuthConfig(pkceEnabled = true)),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, tokenRequests),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val authorizeUrl = Url(noRedirect.get("/auth/redirect/github").headers["Location"]!!)
				val state = authorizeUrl.parameters["state"]
				state.shouldNotBeNull()

				val callback = noRedirect.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}
				callback.status shouldBe HttpStatusCode.OK

				val verifier = tokenRequests.single()["code_verifier"]
				verifier.shouldNotBeNull()
				val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256")
						.digest(verifier.toByteArray(Charsets.US_ASCII)),
				)
				authorizeUrl.parameters["code_challenge"] shouldBe expectedChallenge
			}
		}
	}

	test("OIDC callback replayed with the same state sends the token endpoint no verifier") {
		withTempSessionsDb {
			testApplication {
				val tokenRequests = mutableListOf<Parameters>()
				application {
					module(
						authTestConfig(oidcAuthConfig(pkceEnabled = true)),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, tokenRequests),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val state = Url(noRedirect.get("/auth/redirect/github").headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				repeat(2) {
					noRedirect.get("/auth/callback/github") {
						url {
							parameters.append("code", "authorization-code-from-idp")
							parameters.append("state", state)
						}
					}
				}

				tokenRequests.map { it["code_verifier"] != null } shouldBe listOf(true, false)
			}
		}
	}

	test("OIDC callback sends the token endpoint no verifier when provider.pkce is false") {
		withTempSessionsDb {
			testApplication {
				val tokenRequests = mutableListOf<Parameters>()
				application {
					module(
						authTestConfig(oidcAuthConfig(pkceEnabled = false)),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, tokenRequests),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val state = Url(noRedirect.get("/auth/redirect/github").headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				val callback = noRedirect.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}
				callback.status shouldBe HttpStatusCode.OK

				tokenRequests.single()["code_verifier"].shouldBeNull()
			}
		}
	}

	/**
	 * An OAuth `state` as the server's nonce manager would have issued it [age] ago.
	 *
	 * Ktor's [io.ktor.util.StatelessHmacNonceManager] writes a nonce as `random+time+mac`, where
	 * `time` is `System.nanoTime()` at issue in 16 hex digits and `mac` is the lower-case hex
	 * HMAC-SHA256 of `random:time` under the configured key. Signing an earlier `time` ages a
	 * `state` without waiting for it. `System.nanoTime()` counts from an arbitrary origin, so an
	 * issue time before that origin is written with a sign, which the manager parses all the same.
	 */
	fun agedState(age: Duration): String {
		val issuedAt = System.nanoTime() - age.inWholeNanoseconds
		val time = if (issuedAt >= 0) {
			issuedAt.toString(16).padStart(16, '0')
		} else {
			"-" + (-issuedAt).toString(16).padStart(15, '0')
		}
		val random = "agedstatenonce"
		val mac = Mac.getInstance("HmacSHA256").run {
			init(SecretKeySpec(oauthStateSecret.value.toByteArray(Charsets.UTF_8), "HmacSHA256"))
			doFinal("$random:$time".toByteArray(Charsets.ISO_8859_1))
		}
		return "$random+$time+${mac.joinToString("") { "%02x".format(it) }}"
	}

	/**
	 * Regression test: Ktor's nonce manager accepts a `state` for one minute unless told
	 * otherwise, so a user who took longer than that at the IdP, typing a password and approving
	 * an MFA prompt, came back to a bare `401` while the PKCE verifier was still valid.
	 */
	test("OIDC callback accepts a state issued 90 seconds ago and sends the verifier stored for it") {
		withTempSessionsDb { tempDb ->
			testApplication {
				val tokenRequests = mutableListOf<Parameters>()
				application {
					module(
						authTestConfig(oidcAuthConfig(pkceEnabled = true)),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, tokenRequests),
					)
				}
				val database = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC")
				val pkce = PkceService(ExposedPkceVerifierStore(database).also { it.initSchema() })
				val state = agedState(90.seconds)
				val challenge = pkce.begin(state)

				val callback = createClient { followRedirects = false }.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}

				callback.status shouldBe HttpStatusCode.OK
				val verifier = tokenRequests.single()["code_verifier"]
				verifier.shouldNotBeNull()
				pkce.challengeFor(verifier) shouldBe challenge.challenge
			}
		}
	}

	test("OIDC callback refuses a state older than the login flow's lifetime and leaves its verifier unspent") {
		withTempSessionsDb { tempDb ->
			testApplication {
				val tokenRequests = mutableListOf<Parameters>()
				application {
					module(
						authTestConfig(oidcAuthConfig(pkceEnabled = true)),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, tokenRequests),
					)
				}
				val database = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC")
				val pkce = PkceService(ExposedPkceVerifierStore(database).also { it.initSchema() })
				val state = agedState(LOGIN_FLOW_TTL + 1.seconds)
				pkce.begin(state)

				val callback = createClient { followRedirects = false }.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}

				callback.status shouldBe HttpStatusCode.Unauthorized
				tokenRequests.shouldBeEmpty()
				pkce.consume(state).shouldNotBeNull()
			}
		}
	}

	test("server refuses to start when an OIDC provider is configured without auth.oauthStateSecret") {
		withTempSessionsDb {
			val failure = shouldThrowAny {
				testApplication {
					application {
						module(
							authTestConfig(oidcAuthConfig(pkceEnabled = true).copy(oauthStateSecret = null)),
							authTestSecrets(mapOf("github" to githubClientSecret)),
						)
					}
					startApplication()
				}
			}

			generateSequence(failure) { it.cause }
				.any { it.message.orEmpty().contains("auth.oauthStateSecret is required") } shouldBe true
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

	fun callbackAuthConfig(
		allowedRedirectUris: List<String> = emptyList(),
		allowedEmailDomains: List<String> = listOf("*"),
		assumeEmailVerified: Boolean = false,
	) = authConfig.copy(
		providers = listOf(
			OidcProviderConfig(
				name = "github",
				preset = SsoProviderPreset.GITHUB,
				clientId = "test-client-id",
				allowedEmailDomains = allowedEmailDomains,
				assumeEmailVerified = assumeEmailVerified,
			),
		),
		oauthStateSecret = oauthStateSecret,
		allowedRedirectUris = allowedRedirectUris,
	)

	test("OIDC callback issues a hand-off code and 302s back to returnTo (happy path)") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(callbackAuthConfig(allowedRedirectUris = listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val redirect = noRedirect.get("/auth/redirect/github") {
					url {
						parameters.append("returnTo", "https://app.example.com/")
						parameters.append("handoffChallenge", "challenge-value")
					}
				}
				val state = Url(redirect.headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				val callback = noRedirect.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}
				callback.status shouldBe HttpStatusCode.Found
				val location = callback.headers["Location"]
				location.shouldNotBeNull()
				location shouldStartWith "https://app.example.com/"
				Url(location).parameters["code"].shouldNotBeBlank()
			}
		}
	}

	test("OIDC callback with no parked hand-off returns a session as JSON with the refresh cookie") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(callbackAuthConfig()),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val redirect = noRedirect.get("/auth/redirect/github")
				val state = Url(redirect.headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				val callback = noRedirect.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}
				callback.status shouldBe HttpStatusCode.OK
				val body = Json.parseToJsonElement(callback.bodyAsText()).jsonObject
				body["token"]?.jsonPrimitive?.content.shouldNotBeBlank()
				body["refreshToken"]?.jsonPrimitive?.content.shouldNotBeBlank()
				val setCookie = callback.headers["Set-Cookie"]
				setCookie.shouldNotBeNull()
				setCookie shouldContain "omnisign_refresh="
			}
		}
	}

	test("OIDC callback rejects a user whose email domain is not in allowedEmailDomains") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(callbackAuthConfig(allowedEmailDomains = listOf("other.com"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO),
					)
				}
				val noRedirect = createClient { followRedirects = false }

				val redirect = noRedirect.get("/auth/redirect/github")
				val state = Url(redirect.headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				val callback = noRedirect.get("/auth/callback/github") {
					url {
						parameters.append("code", "authorization-code-from-idp")
						parameters.append("state", state)
					}
				}
				callback.status shouldBe HttpStatusCode.Forbidden
				val body = Json.parseToJsonElement(callback.bodyAsText()).jsonObject
				body["error"]?.jsonPrimitive?.content shouldBe "DOMAIN_NOT_ALLOWED"
			}
		}
	}

	/**
	 * Walk a login through [provider]: start it at `/auth/redirect`, then hit the callback with
	 * the `state` the redirect issued and a code the mocked IdP accepts.
	 *
	 * @return The callback's response.
	 */
	suspend fun ApplicationTestBuilder.completeLogin(provider: String): HttpResponse {
		val noRedirect = createClient { followRedirects = false }
		val redirect = noRedirect.get("/auth/redirect/$provider")
		val state = Url(redirect.headers["Location"]!!).parameters["state"]
		state.shouldNotBeNull()
		return noRedirect.get("/auth/callback/$provider") {
			url {
				parameters.append("code", "authorization-code-from-idp")
				parameters.append("state", state)
			}
		}
	}

	/** The `error` code of an [ApiError][cz.pizavo.omnisign.api.model.responses.ApiError] body. */
	suspend fun HttpResponse.errorCode(): String? =
		Json.parseToJsonElement(bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content

	test("OIDC callback admits a GitHub user whose address the account's email list marks verified") {
		withTempSessionsDb {
			testApplication {
				val requestedUrls = mutableListOf<String>()
				application {
					module(
						authTestConfig(callbackAuthConfig(allowedEmailDomains = listOf("example.com"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(
							OCTOCAT_USER_INFO,
							emailsJson = octocatEmails(verified = true),
							requestedUrls = requestedUrls,
						),
					)
				}

				val callback = completeLogin("github")

				callback.status shouldBe HttpStatusCode.OK
				requestedUrls shouldContain OidcUserInfoService.GITHUB_USER_EMAILS_API_URL
			}
		}
	}

	test("OIDC callback refuses a GitHub user whose address the email list marks unverified, even when assumed verified") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(
							callbackAuthConfig(allowedEmailDomains = listOf("example.com"), assumeEmailVerified = true),
						),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(OCTOCAT_USER_INFO, emailsJson = octocatEmails(verified = false)),
					)
				}

				val callback = completeLogin("github")

				callback.status shouldBe HttpStatusCode.Forbidden
				callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
			}
		}
	}

	test("OIDC callback refuses a GitHub user whose email list cannot be read, as without the user:email scope") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(callbackAuthConfig(allowedEmailDomains = listOf("example.com"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(
							OCTOCAT_USER_INFO,
							emailsJson = """{"message":"Not Found"}""",
							emailsStatus = HttpStatusCode.NotFound,
						),
					)
				}

				val callback = completeLogin("github")

				callback.status shouldBe HttpStatusCode.Forbidden
				callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
			}
		}
	}

	test("OIDC callback admits a GitHub user whose email list cannot be read when the provider assumes verification") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(
							callbackAuthConfig(allowedEmailDomains = listOf("example.com"), assumeEmailVerified = true),
						),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(
							OCTOCAT_USER_INFO,
							emailsJson = """{"message":"Not Found"}""",
							emailsStatus = HttpStatusCode.NotFound,
						),
					)
				}

				completeLogin("github").status shouldBe HttpStatusCode.OK
			}
		}
	}

	test("OIDC callback does not read GitHub's email list under the wildcard") {
		withTempSessionsDb {
			testApplication {
				val requestedUrls = mutableListOf<String>()
				application {
					module(
						authTestConfig(callbackAuthConfig()),
						authTestSecrets(mapOf("github" to githubClientSecret)),
						githubIdpMockEngine(
							OCTOCAT_USER_INFO,
							emailsJson = octocatEmails(verified = false),
							requestedUrls = requestedUrls,
						),
					)
				}

				completeLogin("github").status shouldBe HttpStatusCode.OK
				requestedUrls shouldNotContain OidcUserInfoService.GITHUB_USER_EMAILS_API_URL
			}
		}
	}

	fun idpAuthConfig(
		allowedEmailDomains: List<String>,
		assumeEmailVerified: Boolean = false,
		verifyIdToken: Boolean = false,
	) = authConfig.copy(
		providers = listOf(
			OidcProviderConfig(
				name = "idp",
				clientId = "test-client-id",
				discoveryUrl = TEST_IDP_DISCOVERY_URL,
				allowedEmailDomains = allowedEmailDomains,
				verifyIdToken = verifyIdToken,
				assumeEmailVerified = assumeEmailVerified,
			),
		),
		oauthStateSecret = oauthStateSecret,
	)

	val idpSecrets = mapOf("idp" to "idp-client-secret".sensitive())

	test("server refuses to start when a provider assumes verification on Entra ID's multi-tenant endpoint") {
		withTempSessionsDb {
			val ex = shouldThrow<IllegalArgumentException> {
				testApplication {
					application {
						module(
							authTestConfig(idpAuthConfig(listOf("contoso.com"), assumeEmailVerified = true)),
							authTestSecrets(idpSecrets),
							oidcIdpMockEngine("{}", issuer = "https://login.microsoftonline.com/{tenantid}/v2.0"),
						)
					}
					startApplication()
				}
			}
			ex.message!! shouldContain "'idp'"
			ex.message!! shouldContain "xms_edov"
		}
	}

	test("OIDC callback refuses an address UserInfo marks unverified under a concrete list") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("contoso.com"))),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"alice@contoso.com","email_verified":false}"""),
					)
				}

				val callback = completeLogin("idp")

				callback.status shouldBe HttpStatusCode.Forbidden
				callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
			}
		}
	}

	test("OIDC callback admits an address UserInfo marks verified in Cognito's string form") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("contoso.com"))),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"alice@contoso.com","email_verified":"true"}"""),
					)
				}

				val callback = completeLogin("idp")

				callback.status shouldBe HttpStatusCode.OK
				val user = Json.parseToJsonElement(callback.bodyAsText()).jsonObject["user"]?.jsonObject
				user?.get("email")?.jsonPrimitive?.content shouldBe "alice@contoso.com"
			}
		}
	}

	test("OIDC callback refuses an address UserInfo says nothing about under a concrete list") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("contoso.com"))),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"alice@contoso.com"}"""),
					)
				}

				val callback = completeLogin("idp")

				callback.status shouldBe HttpStatusCode.Forbidden
				callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
			}
		}
	}

	test("OIDC callback admits an address UserInfo says nothing about when the provider assumes verification") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("contoso.com"), assumeEmailVerified = true)),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"alice@contoso.com"}"""),
					)
				}

				completeLogin("idp").status shouldBe HttpStatusCode.OK
			}
		}
	}

	test("OIDC callback admits an address UserInfo marks unverified under the wildcard") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("*"))),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"alice@contoso.com","email_verified":false}"""),
					)
				}

				completeLogin("idp").status shouldBe HttpStatusCode.OK
			}
		}
	}

	test("OIDC callback judges the address the verified id_token signed, and puts it in the session") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken(
						"https://idp.example",
						mapOf("email" to "alice@contoso.com", "email_verified" to true),
					)
					application {
						module(
							authTestConfig(idpAuthConfig(listOf("contoso.com"), verifyIdToken = true)),
							authTestSecrets(idpSecrets),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"mallory@fabrikam.com","email_verified":true}""",
								idToken = idToken,
								jwksUri = jwksUri,
							),
						)
					}

					val callback = completeLogin("idp")

					callback.status shouldBe HttpStatusCode.OK
					val user = Json.parseToJsonElement(callback.bodyAsText()).jsonObject["user"]?.jsonObject
					user?.get("email")?.jsonPrimitive?.content shouldBe "alice@contoso.com"
				}
			}
		}
	}

	test("OIDC callback hands a browser login the address the verified id_token signed") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken(
						"https://idp.example",
						mapOf("email" to "alice@contoso.com", "email_verified" to true),
					)
					application {
						module(
							authTestConfig(
								idpAuthConfig(listOf("contoso.com"), verifyIdToken = true)
									.copy(allowedRedirectUris = listOf("https://app.example.com/")),
							),
							authTestSecrets(idpSecrets),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"mallory@fabrikam.com","email_verified":true}""",
								idToken = idToken,
								jwksUri = jwksUri,
							),
						)
					}
					val verifier = "handoff-verifier-string-kept-by-the-client"
					val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
						MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
					)
					val noRedirect = createClient { followRedirects = false }

					val redirect = noRedirect.get("/auth/redirect/idp") {
						url {
							parameters.append("returnTo", "https://app.example.com/")
							parameters.append("handoffChallenge", challenge)
						}
					}
					val state = Url(redirect.headers["Location"]!!).parameters["state"]
					state.shouldNotBeNull()
					val callback = noRedirect.get("/auth/callback/idp") {
						url {
							parameters.append("code", "authorization-code-from-idp")
							parameters.append("state", state)
						}
					}
					callback.status shouldBe HttpStatusCode.Found
					val code = Url(callback.headers["Location"]!!).parameters["code"]
					code.shouldNotBeNull()

					val exchange = client.post("/auth/exchange") {
						contentType(ContentType.Application.Json)
						setBody("""{"code":"$code","codeVerifier":"$verifier"}""")
					}

					exchange.status shouldBe HttpStatusCode.OK
					val user = Json.parseToJsonElement(exchange.bodyAsText()).jsonObject["user"]?.jsonObject
					user?.get("email")?.jsonPrimitive?.content shouldBe "alice@contoso.com"
				}
			}
		}
	}

	test("OIDC callback refuses an address the verified id_token marks unverified although UserInfo vouches for it") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken(
						"https://idp.example",
						mapOf("email" to "alice@contoso.com", "email_verified" to false),
					)
					application {
						module(
							authTestConfig(idpAuthConfig(listOf("contoso.com"), verifyIdToken = true)),
							authTestSecrets(idpSecrets),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"alice@contoso.com","email_verified":true}""",
								idToken = idToken,
								jwksUri = jwksUri,
							),
						)
					}

					val callback = completeLogin("idp")

					callback.status shouldBe HttpStatusCode.Forbidden
					callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
				}
			}
		}
	}

	test("OIDC callback refuses a login whose id_token marks the address unverified without naming it, even when assumed verified") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken("https://idp.example", mapOf("email_verified" to false))
					application {
						module(
							authTestConfig(
								idpAuthConfig(listOf("contoso.com"), assumeEmailVerified = true, verifyIdToken = true),
							),
							authTestSecrets(idpSecrets),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"alice@contoso.com"}""",
								idToken = idToken,
								jwksUri = jwksUri,
							),
						)
					}

					val callback = completeLogin("idp")

					callback.status shouldBe HttpStatusCode.Forbidden
					callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
				}
			}
		}
	}

	/**
	 * Auth config with one Microsoft provider for [tenantId], by default the single tenant
	 * `contoso.onmicrosoft.com`, restricted to `contoso.com`, verifying its id_tokens.
	 */
	fun microsoftAuthConfig(tenantId: String = "contoso.onmicrosoft.com") = authConfig.copy(
		providers = listOf(
			OidcProviderConfig(
				name = "microsoft",
				preset = SsoProviderPreset.MICROSOFT,
				tenantId = tenantId,
				clientId = "test-client-id",
				allowedEmailDomains = listOf("contoso.com"),
			),
		),
		oauthStateSecret = oauthStateSecret,
	)

	val microsoftDiscoveryUrl =
		"https://login.microsoftonline.com/contoso.onmicrosoft.com/v2.0/.well-known/openid-configuration"
	val microsoftIssuer = "https://login.microsoftonline.com/00000000-0000-0000-0000-00000000c0de/v2.0"

	test("OIDC callback admits a Microsoft user whose id_token carries xms_edov") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken(
						microsoftIssuer,
						mapOf("email" to "alice@contoso.com", "xms_edov" to true),
					)
					application {
						module(
							authTestConfig(microsoftAuthConfig()),
							authTestSecrets(mapOf("microsoft" to "ms-client-secret".sensitive())),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"alice@contoso.com"}""",
								idToken = idToken,
								discoveryUrl = microsoftDiscoveryUrl,
								issuer = microsoftIssuer,
								jwksUri = jwksUri,
							),
						)
					}

					completeLogin("microsoft").status shouldBe HttpStatusCode.OK
				}
			}
		}
	}

	test("OIDC callback refuses a Microsoft user whose id_token lacks xms_edov") {
		withTempSessionsDb {
			withJwksServer { jwksUri ->
				testApplication {
					val idToken = signTestIdToken(microsoftIssuer, mapOf("email" to "alice@contoso.com"))
					application {
						module(
							authTestConfig(microsoftAuthConfig()),
							authTestSecrets(mapOf("microsoft" to "ms-client-secret".sensitive())),
							oidcIdpMockEngine(
								"""{"sub":"u1","email":"alice@contoso.com"}""",
								idToken = idToken,
								discoveryUrl = microsoftDiscoveryUrl,
								issuer = microsoftIssuer,
								jwksUri = jwksUri,
							),
						)
					}

					val callback = completeLogin("microsoft")

					callback.status shouldBe HttpStatusCode.Forbidden
					callback.errorCode() shouldBe "EMAIL_NOT_VERIFIED"
				}
			}
		}
	}

	val tenantTemplate = "https://login.microsoftonline.com/{tenantid}/v2.0"
	val organizationsDiscoveryUrl =
		"https://login.microsoftonline.com/organizations/v2.0/.well-known/openid-configuration"
	val contosoTenant = "aaaabbbb-0000-cccc-1111-dddd2222eeee"

	/**
	 * Walk a login through Microsoft's multi-tenant `organizations` endpoint, whose discovery
	 * document and signing key name Entra ID's `{tenantid}` issuer template, with an id_token
	 * that names [issuer] and carries the tenant [tid].
	 *
	 * @return The callback's status and the `error` code of its body, if any.
	 */
	fun organizationsLogin(issuer: String, tid: String): Pair<HttpStatusCode, String?> =
		withJwksServer(keyIssuer = tenantTemplate) { jwksUri ->
			lateinit var outcome: Pair<HttpStatusCode, String?>
			testApplication {
				val idToken = signTestIdToken(
					issuer,
					mapOf("tid" to tid, "email" to "alice@contoso.com", "xms_edov" to true),
				)
				application {
					module(
						authTestConfig(microsoftAuthConfig(tenantId = "organizations")),
						authTestSecrets(mapOf("microsoft" to "ms-client-secret".sensitive())),
						oidcIdpMockEngine(
							"""{"sub":"u1","email":"alice@contoso.com"}""",
							idToken = idToken,
							discoveryUrl = organizationsDiscoveryUrl,
							issuer = tenantTemplate,
							jwksUri = jwksUri,
						),
					)
				}
				val callback = completeLogin("microsoft")
				outcome = callback.status to callback.errorCode()
			}
			outcome
		}

	test("OIDC callback admits a user of any tenant through Microsoft's multi-tenant endpoint") {
		withTempSessionsDb {
			val (status, error) = organizationsLogin(
				issuer = "https://login.microsoftonline.com/$contosoTenant/v2.0",
				tid = contosoTenant,
			)

			status shouldBe HttpStatusCode.OK
			error shouldBe null
		}
	}

	test("OIDC callback refuses a multi-tenant id_token whose issuer names another tenant than its tid") {
		withTempSessionsDb {
			val (status, error) = organizationsLogin(
				issuer = "https://login.microsoftonline.com/bbbbcccc-1111-dddd-2222-eeee3333ffff/v2.0",
				tid = contosoTenant,
			)

			status shouldBe HttpStatusCode.Unauthorized
			error shouldBe "ID_TOKEN_INVALID"
		}
	}

	test("OIDC callback judges the domain before verification, so an unverified outsider learns only the domain refusal") {
		withTempSessionsDb {
			testApplication {
				application {
					module(
						authTestConfig(idpAuthConfig(listOf("contoso.com"))),
						authTestSecrets(idpSecrets),
						oidcIdpMockEngine("""{"sub":"u1","email":"eve@fabrikam.com","email_verified":false}"""),
					)
				}

				val callback = completeLogin("idp")

				callback.status shouldBe HttpStatusCode.Forbidden
				callback.errorCode() shouldBe "DOMAIN_NOT_ALLOWED"
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

	test("hand-off: the redirect keeps the PKCE verifier and the parked request for the login flow's lifetime") {
		withTempSessionsDb { tempDb ->
			testApplication {
				application {
					module(
						authTestConfig(handoffAuthConfig(listOf("https://app.example.com/"))),
						authTestSecrets(mapOf("github" to githubClientSecret)),
					)
				}
				startApplication()

				val before = Clock.System.now()
				val redirect = createClient { followRedirects = false }.get("/auth/redirect/github") {
					url {
						parameters.append("returnTo", "https://app.example.com/")
						parameters.append("handoffChallenge", "challenge-value")
					}
				}
				val after = Clock.System.now()
				val state = Url(redirect.headers["Location"]!!).parameters["state"]
				state.shouldNotBeNull()

				val database = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC")
				listOf("pkce_verifiers", "login_requests").forEach { table ->
					withClue(table) {
						val expiresAt = transaction(database) {
							exec("SELECT expires_at_epoch_seconds FROM $table WHERE state = '$state'") { rows ->
								if (rows.next()) rows.getLong(1) else null
							}
						}
						expiresAt.shouldNotBeNull()
						expiresAt shouldBeInRange (before + LOGIN_FLOW_TTL).epochSeconds..(after + LOGIN_FLOW_TTL).epochSeconds
					}
				}
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




