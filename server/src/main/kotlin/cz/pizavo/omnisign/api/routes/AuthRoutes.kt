package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.requests.ExchangeCodeRequest
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
import kotlin.time.Duration
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
 * - `GET /auth/redirect/{provider}` — OIDC providers only. Carries no handler body: the
 *   enclosing `authenticate("oidc-{provider}") { }` block makes Ktor's OAuth provider issue
 *   a `302` to the IdP's authorization endpoint, appending `state` and (when
 *   [OidcProviderConfig.pkce] is enabled) the PKCE `code_challenge`.
 *
 *   A browser app additionally passes `?returnTo=…&handoffChallenge=…` here to ask for the
 *   hand-off described below; [cz.pizavo.omnisign.plugins.configureAuthentication] parks them
 *   against this flow's `state`.
 *
 * - `GET /auth/callback/{provider}` — OAuth2 authorization-code callback for OIDC providers
 *   (or trusted-header injection callback for Shibboleth-style providers). Resolves the
 *   user identity, then mints **both** a short-lived JWT access token and a long-lived
 *   opaque refresh token (persisted in the [RefreshTokenStore]).
 *
 *   How it answers depends on who asked. A plain hit — an API client, a header-injection
 *   proxy, an operator testing the flow by hand — gets [TokenResponse] as JSON. A hit that
 *   began with a `returnTo` gets a `302` back to that page carrying a single-use hand-off
 *   code, because a single-page app cannot read a JSON body the browser navigated to. The
 *   tokens themselves are deliberately not what travels: a URL is logged, remembered, and
 *   passed on in `Referer`, so what travels is a code that is worthless without the verifier
 *   the app kept, and the app trades it at `/auth/exchange`. `returnTo` must match
 *   `auth.allowedRedirectUris` exactly or the login is refused with
 *   `400 REDIRECT_URI_NOT_ALLOWED` — see [isRedirectUriAllowed] for why nothing looser will do.
 *
 *   The OIDC callback sits inside the **same** `authenticate("oidc-{provider}") { }` block as
 *   its `/auth/redirect/{provider}` counterpart. That is load-bearing rather than stylistic:
 *   Ktor runs the OAuth token exchange only for routes enclosed by that block, so a callback
 *   mounted outside it would always observe a `null`
 *   [OAuthAccessTokenResponse.OAuth2][io.ktor.server.auth.OAuthAccessTokenResponse.OAuth2]
 *   principal and reject every login with `401 OAUTH_FAILED`.
 *
 * - `POST /auth/exchange` — accepts an [ExchangeCodeRequest] and redeems a hand-off code for
 *   a real session, answering exactly as the callback's JSON branch would. Rejects with
 *   `401 INVALID_HANDOFF_CODE` when the code is unknown / expired / already-redeemed / not
 *   bound to the presented verifier, `400 MISSING_HANDOFF_CODE` on a malformed body, and
 *   `503 AUTH_NOT_CONFIGURED` when no auth providers are configured.
 *
 * - `GET /auth/session` — returns [SessionResponse] for the caller identified by a valid
 *   JWT Bearer token, or `401 Unauthorized` when no valid token is present.
 *
 * - `POST /auth/refresh` — atomically consumes the refresh token (single-use rotation) and
 *   mints a fresh access-token + refresh-token pair preserving the original `auth_time`. The
 *   token is taken from a [RefreshTokenRequest] body when present, otherwise from the
 *   [REFRESH_TOKEN_COOKIE] cookie: an API client has only the body, and a browser app that
 *   has just been reloaded has only the cookie, since its in-memory copy died with the page.
 *   Rejects with `401 SESSION_EXPIRED` when the total session age exceeds
 *   [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds], `401 INVALID_REFRESH_TOKEN`
 *   when the token is unknown / expired / already-consumed, `400 MISSING_REFRESH_TOKEN` when
 *   neither channel carries one, `503 AUTH_NOT_CONFIGURED` when no auth providers are
 *   configured.
 *
 * - `POST /auth/logout` — accepts a [RefreshTokenRequest] body, deletes that token from the
 *   store, and clears the refresh cookie. Idempotent — always returns `204 No Content`.
 *   Takes the token from the body, not the cookie: the cookie is scoped to `/auth/refresh`, so
 *   the browser does not send it here. A client still holding its refresh token — an API client,
 *   or a browser tab that has not been reloaded since its last issue/refresh — revokes
 *   server-side and clears its cookie in one call. A browser reloaded since its last refresh has
 *   lost the in-memory token and can only clear its own cookie; that session's refresh-token row
 *   then lingers until it expires or [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds]
 *   elapses. Closing that gap is a client concern (refresh first, then log out with the fresh
 *   token), not a server one — widening the cookie's path to reach `/auth/logout` would hand the
 *   credential to more endpoints than need it, which is the opposite of what the scope is for.
 *
 * `/auth/login`, `/auth/refresh`, `/auth/exchange`, `/auth/logout`, and the callbacks are
 * mounted unconditionally so clients can authenticate (or end their session) without a JWT.
 * `/auth/session` is the only route that requires a valid Bearer JWT.
 *
 * @param config Root authentication configuration, or `null` when auth is disabled.
 * @param secureCookies Whether the refresh cookie is marked `Secure`; `true` whenever the
 *   deployment terminates TLS itself or sits behind a proxy that does.
 */
fun Route.authRoutes(config: AuthConfig?, secureCookies: Boolean = false) {
    val jwtService by inject<JwtSessionService>()
    val discoveryService by inject<OidcDiscoveryService>()
    val userInfoService by inject<OidcUserInfoService>()
    val idTokenVerifier by inject<IdTokenVerifier>()
    val refreshTokenStore by inject<RefreshTokenStore>()
    val loginRequestStore by inject<LoginRequestStore>()
    val handoffCodeStore by inject<HandoffCodeStore>()

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

                get("/callback/${provider.name}") {
                    val oauthPrincipal = call.principal<OAuthAccessTokenResponse.OAuth2>()
                    if (oauthPrincipal == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError(error = "OAUTH_FAILED", message = "OAuth2 authorization failed for provider '${provider.name}'"),
                        )
                        return@get
                    }

                    val shouldVerifyIdToken =
                        provider.verifyIdToken && provider.preset?.requiresManualUrls != true
                    val verifiedIdToken: VerifiedIdToken? = if (shouldVerifyIdToken) {
                        val idTokenString = oauthPrincipal.extraParameters["id_token"]
                        if (idTokenString.isNullOrBlank()) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ApiError(
                                    error = "ID_TOKEN_MISSING",
                                    message = "Provider '${provider.name}' did not return an id_token. " +
                                        "Either the IdP is not OIDC-compliant or the `openid` scope was rejected; " +
                                        "set verifyIdToken: false in server.yml for non-OIDC providers.",
                                ),
                            )
                            return@get
                        }
                        try {
                            idTokenVerifier.verify(provider, idTokenString)
                        } catch (e: IdTokenVerificationException) {
                            logger.warn(e) { "id_token verification failed for provider '${provider.name}'" }
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ApiError(
                                    error = "ID_TOKEN_INVALID",
                                    message = "id_token from provider '${provider.name}' failed verification: " +
                                        "${e.message}",
                                ),
                            )
                            return@get
                        }
                    } else {
                        null
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

                    if (verifiedIdToken != null && verifiedIdToken.subject != result.principal.userId) {
                        logger.warn {
                            "id_token sub ('${verifiedIdToken.subject}') does not match UserInfo sub " +
                                "('${result.principal.userId}') for provider '${provider.name}' — possible " +
                                "UserInfo substitution or IdP misconfiguration"
                        }
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError(
                                error = "ID_TOKEN_SUB_MISMATCH",
                                message = "id_token subject does not match UserInfo subject; " +
                                    "rejecting the login to avoid trusting a substituted identity.",
                            ),
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

                    val loginRequest = call.request.queryParameters["state"]
                        ?.let { loginRequestStore.consume(it) }
                    if (loginRequest == null) {
                        respondWithTokens(
                            call, result.principal, jwtService, refreshTokenStore,
                            config.session, secureCookies,
                        )
                        return@get
                    }

                    if (!isRedirectUriAllowed(loginRequest.returnTo, config.allowedRedirectUris)) {
                        logger.warn {
                            "Refused a login hand-off to '${loginRequest.returnTo}' — not listed in " +
                                "auth.allowedRedirectUris. Either the front-end is configured with the " +
                                "wrong URL, or someone is trying to have a hand-off code delivered to a " +
                                "page they control."
                        }
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                error = "REDIRECT_URI_NOT_ALLOWED",
                                message = "This server will not return a login to that address. Add it to " +
                                    "auth.allowedRedirectUris in server.yml if it is your front-end.",
                            ),
                        )
                        return@get
                    }

                    val handoffCode = handoffCodeStore.issue(
                        principal = result.principal,
                        handoffChallenge = loginRequest.handoffChallenge,
                        ttl = HANDOFF_CODE_TTL,
                    )
                    call.respondRedirect(appendHandoffCode(loginRequest.returnTo, handoffCode))
                }
            }
        }

        config?.providers?.filterIsInstance<HeaderInjectionProviderConfig>()?.forEach { provider ->
            get("/callback/${provider.name}") {
                val providedSecret = call.request.headers[provider.sharedSecretHeader]
                if (providedSecret == null || !sharedSecretMatches(providedSecret, provider.sharedSecret.value)) {
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
                val displayName = call.request.headers[provider.displayNameHeader]

                val principal = AuthenticatedPrincipal(
                    userId = userId,
                    email = email,
                    displayName = displayName,
                    providerName = provider.name,
                    authTime = Clock.System.now(),
                )

                respondWithTokens(call, principal, jwtService, refreshTokenStore, config.session, secureCookies)
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

            val bodyToken = runCatching { call.receive<RefreshTokenRequest>() }.getOrNull()
                ?.refreshToken?.takeIf { it.isNotBlank() }
            val presentedToken = bodyToken ?: call.refreshCookie()
            if (presentedToken == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        error = "MISSING_REFRESH_TOKEN",
                        message = "Send the refresh token as a JSON body with a non-empty `refreshToken` " +
                            "field, or as the `$REFRESH_TOKEN_COOKIE` cookie.",
                    ),
                )
                return@post
            }

            val principal = refreshTokenStore.consume(presentedToken)
            if (principal == null) {
                if (bodyToken == null) {
                    call.response.clearRefreshCookie(secureCookies)
                }
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
                call.response.clearRefreshCookie(secureCookies)
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

            respondWithTokens(call, principal, jwtService, refreshTokenStore, config.session, secureCookies)
        }

        post("/exchange") {
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

            val request = runCatching { call.receive<ExchangeCodeRequest>() }.getOrNull()
            if (request == null || request.code.isBlank() || request.codeVerifier.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        error = "MISSING_HANDOFF_CODE",
                        message = "Request body must be a JSON object with non-empty `code` and " +
                            "`codeVerifier` fields.",
                    ),
                )
                return@post
            }

            val principal = handoffCodeStore.consume(request.code, request.codeVerifier)
            if (principal == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(
                        error = "INVALID_HANDOFF_CODE",
                        message = "Hand-off code is unknown, expired, already redeemed, or was not " +
                            "issued to the client presenting it. Start the login again.",
                    ),
                )
                return@post
            }

            respondWithTokens(call, principal, jwtService, refreshTokenStore, config.session, secureCookies)
        }

        post("/logout") {
            val request = runCatching { call.receive<RefreshTokenRequest>() }.getOrNull()
            if (request != null && request.refreshToken.isNotBlank()) {
                refreshTokenStore.delete(request.refreshToken)
            }
            call.response.clearRefreshCookie(secureCookies)
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
 * Mint a fresh access + refresh token pair for [principal], set the refresh cookie, and write
 * a [TokenResponse] JSON body to [call].
 *
 * The refresh token is persisted in [refreshTokenStore] with TTL
 * [cz.pizavo.omnisign.config.SessionConfig.refreshTokenLifetimeSeconds]; the access token
 * is a signed JWT with TTL [cz.pizavo.omnisign.config.SessionConfig.tokenExpirySeconds].
 * Every route that starts or continues a session funnels through here — the login callbacks,
 * `/auth/exchange`, and `/auth/refresh`, the last of which passes the principal returned by
 * [RefreshTokenStore.consume], whose `authTime` is the original SSO authentication instant
 * (preserved across rotation).
 *
 * The refresh token goes out through **both** channels, every time, and that is load-bearing
 * rather than belt-and-braces. The two channels serve different clients and different moments:
 *
 * - The **body** is what a non-browser client reads, and what a browser app holds in memory to
 *   refresh with while the page is alive. It is also the only channel that works at all for an
 *   app deployed cross-site from the API, where the cookie will never come back.
 * - The **cookie** is what survives a page reload, which memory does not. It is what lets a
 *   returning user resume a session instead of bouncing through the identity provider again.
 *
 * Because refresh rotates the token, the two copies would diverge the moment only one of them
 * were updated: a browser that refreshed from memory would leave a consumed token in its cookie
 * and get logged out on its next reload, for no reason it could see. Writing both on every
 * issue keeps them the same value at every point in the sequence, so it never matters which one
 * the client comes back with.
 *
 * @param secureCookies Whether the refresh cookie carries `Secure` — see [setRefreshCookie].
 */
private suspend fun respondWithTokens(
    call: RoutingCall,
    principal: AuthenticatedPrincipal,
    jwtService: JwtSessionService,
    refreshTokenStore: RefreshTokenStore,
    sessionConfig: cz.pizavo.omnisign.config.SessionConfig,
    secureCookies: Boolean,
) {
    val jwt = jwtService.issue(principal)
    val refresh = refreshTokenStore.issue(principal, sessionConfig.refreshTokenLifetimeSeconds.seconds)
    call.response.setRefreshCookie(refresh.token, secureCookies)
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

/**
 * Append the hand-off [code] to [returnTo] as a query parameter, preserving anything already
 * in the URL.
 *
 * [returnTo] has already been matched verbatim against `auth.allowedRedirectUris`, so this is
 * building on a value the operator wrote rather than one a caller supplied.
 *
 * @param returnTo An allowlisted absolute URL.
 * @param code The single-use hand-off code.
 * @return The redirect target.
 */
private fun appendHandoffCode(returnTo: String, code: String): String =
    URLBuilder(returnTo).apply { parameters.append(HANDOFF_CODE_PARAMETER, code) }.buildString()

/**
 * Query parameter the hand-off code arrives in at the single-page app.
 */
const val HANDOFF_CODE_PARAMETER: String = "code"

/**
 * How long a hand-off code stays redeemable.
 *
 * Thirty seconds. The code is minted by a redirect and redeemed by the page that redirect
 * lands on, so the window covers a browser navigation and a script's first fetch — no human is
 * in the loop and nothing here waits on one. Anything longer only widens the interval in which
 * a code sitting in a URL, browser history, or a proxy log is still worth something.
 */
private val HANDOFF_CODE_TTL: Duration = 30.seconds
