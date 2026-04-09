package cz.pizavo.omnisign.api.routes

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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

private val logger = KotlinLogging.logger {}

/**
 * Mount authentication routes under `/auth`.
 *
 * ### Routes
 *
 * - `GET /auth/login` — returns [LoginOptionsResponse] listing all active providers and
 *   their login URLs. The browser or web UI should redirect to `loginUrl` of the chosen entry.
 *
 * - `GET /auth/callback/{provider}` — OAuth2 authorization-code callback for OIDC providers.
 *   Exchanges the code for an access token, fetches user claims via the IdP's `/userinfo`
 *   endpoint, and responds with a [TokenResponse] containing the OmniSign JWT session token.
 *   For [HeaderInjectionProviderConfig] providers the user identity is read directly from
 *   the request headers set by the upstream Shibboleth SP.
 *
 * - `GET /auth/session` — returns [SessionResponse] for the caller identified by a valid
 *   JWT Bearer token, or `401 Unauthorized` when no valid token is present.
 *
 * - `POST /auth/refresh` — issues a new JWT with a fully-reset expiry for the caller
 *   identified by a valid Bearer token. Allows long-running sessions without re-login.
 *   Returns `503` when no auth providers are configured.
 *
 * - `POST /auth/logout` — stateless acknowledgement (JWTs are self-contained; real revocation
 *   requires a deny-list, which is a future extension). Always returns `204 No Content`.
 *
 * These routes are always mounted regardless of `requireLogin` so that clients
 * can authenticate even before the application reports them as logged in.
 *
 * @param config Root authentication configuration, or `null` when auth is disabled.
 */
fun Route.authRoutes(config: AuthConfig?) {
    val jwtService by inject<JwtSessionService>()
    val discoveryService by inject<OidcDiscoveryService>()
    val userInfoService by inject<OidcUserInfoService>()

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

                respondWithToken(call, result.principal, jwtService, config.session.tokenExpirySeconds)
            }
        }

        config?.providers?.filterIsInstance<HeaderInjectionProviderConfig>()?.forEach { provider ->
            get("/callback/${provider.name}") {
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
                )

                respondWithToken(call, principal, jwtService, config.session.tokenExpirySeconds)
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

            post("/refresh") {
                val principal = call.principal<AuthenticatedPrincipal>()
                    ?: run {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError(error = "UNAUTHENTICATED", message = "No valid session token"),
                        )
                        return@post
                    }

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

                respondWithToken(call, principal, jwtService, config.session.tokenExpirySeconds)
            }
        }

        post("/logout") {
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
 * Issue a JWT for [principal] and write a [TokenResponse] JSON body to [call].
 */
private suspend fun respondWithToken(
    call: RoutingCall,
    principal: AuthenticatedPrincipal,
    jwtService: JwtSessionService,
    expiresIn: Long,
) {
    val token = jwtService.issue(principal)
    call.respond(
        TokenResponse(
            token = token,
            expiresIn = expiresIn,
            user = SessionResponse(
                userId = principal.userId,
                email = principal.email,
                displayName = principal.displayName,
                providerName = principal.providerName,
            ),
        ),
    )
}
