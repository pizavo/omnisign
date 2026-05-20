package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.requests.RefreshTokenRequest
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.LoginOptionsResponse
import cz.pizavo.omnisign.api.model.responses.SessionResponse
import cz.pizavo.omnisign.api.model.responses.TokenResponse
import cz.pizavo.omnisign.auth.*
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.HeaderInjectionProviderConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Mount authentication routes under `/auth`.
 *
 * ### Routes
 *
 * - `GET /auth/login` — returns [LoginOptionsResponse] listing all active providers and
 *   their login URLs. The browser or web UI should redirect to `loginUrl` of the chosen entry.
 *
 * - `GET /auth/callback/{provider}` — OAuth2 authorization-code callback for OIDC providers
 *   (or trusted-header injection callback for Shibboleth-style providers). Resolves the
 *   user identity, then mints **both** a short-lived JWT access token and a long-lived
 *   opaque refresh token (persisted in the [RefreshTokenStore]). Both are returned in
 *   [TokenResponse].
 *
 * - `GET /auth/session` — returns [SessionResponse] for the caller identified by a valid
 *   JWT Bearer token, or `401 Unauthorized` when no valid token is present.
 *
 * - `POST /auth/refresh` — accepts a [RefreshTokenRequest] body, atomically consumes the
 *   refresh token (single-use rotation), and mints a fresh access-token + refresh-token
 *   pair preserving the original `auth_time`. Rejects with `401 SESSION_EXPIRED` when the
 *   total session age exceeds [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds],
 *   `401 INVALID_REFRESH_TOKEN` when the token is unknown / expired / already-consumed,
 *   `503 AUTH_NOT_CONFIGURED` when no auth providers are configured.
 *
 * - `POST /auth/logout` — accepts a [RefreshTokenRequest] body and deletes the token from
 *   the store. Idempotent — always returns `204 No Content`.
 *
 * `/auth/login`, `/auth/refresh`, `/auth/logout`, and the callbacks are mounted
 * unconditionally so clients can authenticate (or end their session) without a JWT.
 * `/auth/session` is the only route that requires a valid Bearer JWT.
 *
 * @param config Root authentication configuration, or `null` when auth is disabled.
 */
fun Route.authRoutes(config: AuthConfig?) {
    val jwtService by inject<JwtSessionService>()
    val discoveryService by inject<OidcDiscoveryService>()
    val userInfoService by inject<OidcUserInfoService>()
    val refreshTokenStore by inject<RefreshTokenStore>()

    route("/auth") {
        get("/login") {
            if (config == null || config.providers.isEmpty()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError(
                        error = "AUTH_NOT_CONFIGURED",
                        message = "Authentication is not configured on this server",
                    ),
                )
                return@get
            }

            val providers = config.providers.map { provider ->
                LoginOptionsResponse.ProviderInfo(
                    name = provider.name,
                    displayName = when (provider) {
                        is OidcProviderConfig -> provider.displayName
                        is HeaderInjectionProviderConfig -> provider.displayName
                    },
                    type = when (provider) {
                        is OidcProviderConfig -> "oidc"
                        is HeaderInjectionProviderConfig -> "header-injection"
                    },
                    loginUrl = when (provider) {
                        is OidcProviderConfig ->
                            "/auth/redirect/${provider.name}"
                        is HeaderInjectionProviderConfig ->
                            "/auth/callback/${provider.name}"
                    },
                )
            }
            call.respond(LoginOptionsResponse(providers))
        }

        config?.providers?.filterIsInstance<OidcProviderConfig>()?.forEach { provider ->
            val oidcAuthName = "${JwtSessionService.AUTH_NAME_OIDC_PREFIX}${provider.name}"

            authenticate(oidcAuthName) {
                get("/redirect/${provider.name}") {
                }
            }

            get("/callback/${provider.name}") {
                val oauthPrincipal = call.principal<OAuthAccessTokenResponse.OAuth2>()
                if (oauthPrincipal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError(error = "OAUTH_FAILED", message = "OAuth2 authorization failed for provider '${provider.name}'"),
                    )
                    return@get
                }

                val result = resolvePrincipalFromOidc(
                    provider = provider,
                    oauthToken = oauthPrincipal,
                    discoveryService = discoveryService,
                    userInfoService = userInfoService,
                )

                if (result == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError(error = "USERINFO_FAILED", message = "Could not resolve user identity from provider '${provider.name}'"),
                    )
                    return@get
                }

                if (!isEmailDomainAllowed(result.principal.email, provider.allowedEmailDomains)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError(
                            error = "DOMAIN_NOT_ALLOWED",
                            message = "Your account domain is not permitted to access this server.",
                        ),
                    )
                    return@get
                }

                if (!areRequiredClaimsSatisfied(result.claims, provider.requiredClaims)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError(
                            error = "CLAIMS_NOT_SATISFIED",
                            message = "Your account does not satisfy the required claim constraints for this server.",
                        ),
                    )
                    return@get
                }

                respondWithTokens(call, result.principal, jwtService, refreshTokenStore, config.session)
            }
        }

        config?.providers?.filterIsInstance<HeaderInjectionProviderConfig>()?.forEach { provider ->
            get("/callback/${provider.name}") {
                val providedSecret = call.request.headers[provider.sharedSecretHeader]
                if (providedSecret == null || !sharedSecretMatches(providedSecret, provider.sharedSecret)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError(
                            error = "INVALID_HEADER_INJECTION_TOKEN",
                            message = "Header-injection callback rejected: missing or invalid " +
                                    "'${provider.sharedSecretHeader}' header. The trusted upstream proxy must " +
                                    "inject this header with the configured shared secret on every authenticated request.",
                        ),
                    )
                    return@get
                }

                val userId = call.request.headers[provider.userHeader]
                if (userId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError(
                            error = "MISSING_SHIB_HEADER",
                            message = "Expected header '${provider.userHeader}' was not present. " +
                                    "Ensure the Shibboleth SP reverse proxy is correctly configured.",
                        ),
                    )
                    return@get
                }

                val email = call.request.headers[provider.emailHeader]
                    ?: userId
                val displayName = call.request.headers[provider.displayNameHeader]

                val principal = AuthenticatedPrincipal(
                    userId = userId,
                    email = email,
                    displayName = displayName,
                    providerName = provider.name,
                    authTime = Clock.System.now(),
                )

                respondWithTokens(call, principal, jwtService, refreshTokenStore, config.session)
            }
        }

        authenticate(JwtSessionService.AUTH_NAME_JWT) {
            get("/session") {
                val principal = call.principal<AuthenticatedPrincipal>()
                    ?: run {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError(error = "UNAUTHENTICATED", message = "No valid session token"),
                        )
                        return@get
                    }

                call.respond(
                    SessionResponse(
                        userId = principal.userId,
                        email = principal.email,
                        displayName = principal.displayName,
                        providerName = principal.providerName,
                    ),
                )
            }
        }

        post("/refresh") {
            if (config == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError(
                        error = "AUTH_NOT_CONFIGURED",
                        message = "Authentication is not configured on this server",
                    ),
                )
                return@post
            }

            val request = runCatching { call.receive<RefreshTokenRequest>() }.getOrNull()
            if (request == null || request.refreshToken.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        error = "MISSING_REFRESH_TOKEN",
                        message = "Request body must be a JSON object with a non-empty `refreshToken` field.",
                    ),
                )
                return@post
            }

            val principal = refreshTokenStore.consume(request.refreshToken)
            if (principal == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(
                        error = "INVALID_REFRESH_TOKEN",
                        message = "Refresh token is unknown, expired, or has already been used. Re-authenticate via the identity provider.",
                    ),
                )
                return@post
            }

            val sessionAge = Clock.System.now().epochSeconds - principal.authTime.epochSeconds
            if (sessionAge > config.session.maxSessionSeconds) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(
                        error = "SESSION_EXPIRED",
                        message = "Session exceeded the maximum lifetime of " +
                                "${config.session.maxSessionSeconds} seconds since the original " +
                                "authentication. Re-authenticate via the identity provider.",
                    ),
                )
                return@post
            }

            respondWithTokens(call, principal, jwtService, refreshTokenStore, config.session)
        }

        post("/logout") {
            val request = runCatching { call.receive<RefreshTokenRequest>() }.getOrNull()
            if (request != null && request.refreshToken.isNotBlank()) {
                refreshTokenStore.delete(request.refreshToken)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Fetch OIDC user claims and map them to an [OidcAuthResult].
 *
 * Uses the [OidcDiscoveryService] to find the UserInfo endpoint, then calls
 * [OidcUserInfoService.fetchRawClaims] with the access token from [oauthToken].
 * Falls back to the GitHub user API for providers with [SsoProviderPreset.GITHUB].
 * The raw [JsonObject][kotlinx.serialization.json.JsonObject] claims are
 * preserved in [OidcAuthResult] so that post-login filters such as
 * [areRequiredClaimsSatisfied] can inspect provider-specific attributes.
 *
 * @return The resolved [OidcAuthResult], or `null` on failure.
 */
private suspend fun resolvePrincipalFromOidc(
    provider: OidcProviderConfig,
    oauthToken: OAuthAccessTokenResponse.OAuth2,
    discoveryService: OidcDiscoveryService,
    userInfoService: OidcUserInfoService,
): OidcAuthResult? {
    return try {
        val userInfoUrl = if (provider.preset == SsoProviderPreset.GITHUB) {
            OidcDiscoveryService.GITHUB_USER_API_URL
        } else {
            val doc = discoveryService.discover(provider)
            doc.userInfoEndpoint
                ?: return null.also {
                    logger.warn { "Provider '${provider.name}' discovery document has no userinfo_endpoint" }
                }
        }

        val rawClaims = userInfoService.fetchRawClaims(userInfoUrl, oauthToken.accessToken)
        val principal = userInfoService.toPrincipal(rawClaims, provider.name)
        OidcAuthResult(principal, rawClaims)
    } catch (ex: Exception) {
        logger.error(ex) { "Failed to resolve user info for provider '${provider.name}'" }
        null
    }
}

/**
 * Compare a client-supplied shared secret against the configured value in constant time.
 *
 * Uses [MessageDigest.isEqual] which performs a length-independent byte comparison after
 * the length check, avoiding timing side channels that a naive `==` on Strings would
 * expose. Returns `false` immediately when lengths differ — a minor leak for HMAC-style
 * fixed-length secrets, irrelevant here because the configured secret is ≥32 bytes
 * (enforced by [HeaderInjectionProviderConfig]) so brute-forcing the length is not the
 * weak link.
 *
 * @param provided The value supplied by the inbound request header.
 * @param expected The configured shared secret from the provider's config.
 * @return `true` when the two values are byte-for-byte equal.
 */
private fun sharedSecretMatches(provided: String, expected: String): Boolean =
    MessageDigest.isEqual(
        provided.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )

/**
 * Mint a fresh access + refresh token pair for [principal] and write a [TokenResponse]
 * JSON body to [call].
 *
 * The refresh token is persisted in [refreshTokenStore] with TTL
 * [cz.pizavo.omnisign.config.SessionConfig.refreshTokenLifetimeSeconds]; the access token
 * is a signed JWT with TTL [cz.pizavo.omnisign.config.SessionConfig.tokenExpirySeconds].
 * Called from both initial-login callbacks and `/auth/refresh` — the latter passes the
 * principal returned by [RefreshTokenStore.consume], whose `authTime` is the original
 * SSO authentication instant (preserved across rotation).
 */
private suspend fun respondWithTokens(
    call: RoutingCall,
    principal: AuthenticatedPrincipal,
    jwtService: JwtSessionService,
    refreshTokenStore: RefreshTokenStore,
    sessionConfig: cz.pizavo.omnisign.config.SessionConfig,
) {
    val jwt = jwtService.issue(principal)
    val refresh = refreshTokenStore.issue(principal, sessionConfig.refreshTokenLifetimeSeconds.seconds)
    call.respond(
        TokenResponse(
            token = jwt,
            refreshToken = refresh.token,
            expiresIn = sessionConfig.tokenExpirySeconds,
            user = SessionResponse(
                userId = principal.userId,
                email = principal.email,
                displayName = principal.displayName,
                providerName = principal.providerName,
            ),
        ),
    )
}
