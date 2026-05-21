package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.PkceService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.HeaderInjectionProviderConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.util.NonceManager
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject

private val logger = KotlinLogging.logger {}

/**
 * Install and configure the Ktor [Authentication] plugin.
 *
 * This function is called unconditionally during server bootstrap so that
 * `authenticate {}` blocks in routes (e.g. `/auth/session`) always have a registered
 * provider to reference, even when authentication is disabled.
 *
 * Two authentication provider families are registered:
 *
 * 1. **`jwt-api`** ([JwtSessionService.AUTH_NAME_JWT]) — validates HMAC-signed JWT
 *    (HS256/HS384/HS512) Bearer tokens. When [config] is `null` the provider is
 *    registered but always challenges with `401` (no valid token can ever be produced
 *    without a configured secret), effectively disabling authentication while keeping
 *    the plugin installed.
 *
 * 2. **`oidc-{name}`** — one [OAuthServerSettings.OAuth2ServerSettings] block per
 *    [OidcProviderConfig] in [config]. Each provider's authorization and token endpoints
 *    are resolved from the OIDC discovery document (or hard-coded for GitHub). These
 *    providers are used exclusively by the `/auth/callback/{name}` route.
 *
 *    Each `oauth { … }` block is configured with the injected [NonceManager] so the
 *    OAuth2 `state` parameter is verified on the authorization-code callback. Ktor's
 *    default `nonceManager` is `GenerateOnlyNonceManager` whose `verifyNonce()` returns
 *    `true` unconditionally — accepting any state value and exposing the flow to
 *    login-CSRF / account-fixation attacks. Wiring a [StatelessHmacNonceManager] here
 *    closes that gap; the HMAC key comes from `OMNISIGN_OAUTH_NONCE_SECRET` (see
 *    [serverModule][cz.pizavo.omnisign.di.serverModule]).
 *
 *    Each `oauth { … }` block also performs PKCE (RFC 7636) via [PkceService] when
 *    [OidcProviderConfig.pkce] is `true` (the default). `authorizeUrlInterceptor`
 *    appends `code_challenge` + `code_challenge_method=S256` to the authorize URL,
 *    keyed on the OAuth `state` parameter Ktor has just generated. `providerLookup`
 *    consumes the matching verifier from [PkceService] on the callback hop and
 *    injects it into the token-exchange POST via `extraTokenParameters`. PKCE binds
 *    the authorization code to the entity that originated the flow, defending against
 *    code-injection attacks even if the client secret is leaked (RFC 9700 / OAuth 2.1
 *    requires this for all clients).
 *
 * [HeaderInjectionProviderConfig] providers are not registered here; they are handled
 * directly in the `/auth/callback/{name}` route by reading the injected request headers.
 *
 * @param config Root authentication configuration, or `null` when auth is disabled.
 * @param externalUrl Base public URL of the server used to build OAuth2 redirect URIs.
 *   Ignored when [config] is `null`.
 */
fun Application.configureAuthentication(config: AuthConfig?, externalUrl: String = "") {
    val jwtService by inject<JwtSessionService>()
    val discoveryService by inject<OidcDiscoveryService>()
    val oauthNonceManager by inject<NonceManager>()
    val pkceService by inject<PkceService>()
    val serverSecrets by inject<ServerSecrets>()

    install(Authentication) {
        bearer(JwtSessionService.AUTH_NAME_JWT) {
            authenticate { tokenCredential ->
                jwtService.verify(tokenCredential.token)
            }
        }

        config?.providers?.filterIsInstance<OidcProviderConfig>()?.forEach { provider ->
            val authName = "${JwtSessionService.AUTH_NAME_OIDC_PREFIX}${provider.name}"
            val redirectUrl = "$externalUrl/auth/callback/${provider.name}"

            val (authUrl, tokenUrl) = resolveEndpoints(provider, discoveryService)
            val clientSecret = checkNotNull(serverSecrets.oidcClientSecrets[provider.name]) {
                "OIDC client secret for provider '${provider.name}' was not resolved at startup — " +
                    "this is a programming error in ServerSecrets.resolveFromEnv (every configured " +
                    "OIDC provider should have its env var resolved)."
            }

            oauth(authName) {
                urlProvider = { redirectUrl }
                providerLookup = {
                    val verifier: String? = if (provider.pkce) {
                        parameters["state"]?.let { state ->
                            runBlocking { pkceService.consume(state) }
                        }
                    } else {
                        null
                    }

                    val extraTokenParams: List<Pair<String, String>> = listOfNotNull(
                        verifier?.let { "code_verifier" to it },
                    )

                    OAuthServerSettings.OAuth2ServerSettings(
                        name = provider.name,
                        authorizeUrl = authUrl,
                        accessTokenUrl = tokenUrl,
                        clientId = provider.clientId,
                        clientSecret = clientSecret.value,
                        requestMethod = io.ktor.http.HttpMethod.Post,
                        defaultScopes = provider.scopes,
                        nonceManager = oauthNonceManager,
                        extraTokenParameters = extraTokenParams,
                        authorizeUrlInterceptor = {
                            if (provider.pkce) {
                                val state = parameters["state"]
                                    ?: error(
                                        "Ktor OAuth should have appended `state` to the authorize URL " +
                                            "before authorizeUrlInterceptor runs — got null for provider '${provider.name}'",
                                    )
                                val challenge = runBlocking { pkceService.begin(state) }
                                parameters.append("code_challenge", challenge.challenge)
                                parameters.append("code_challenge_method", challenge.method)
                            }
                        },
                    )
                }
                client = HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = OAUTH_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = OAUTH_SOCKET_TIMEOUT_MS
                    }
                }
            }

            val pkceLabel = if (provider.pkce) " — PKCE enabled" else " — PKCE disabled"
            logger.info { "Registered OIDC provider '${provider.name}' (${provider.displayName}) — redirect: $redirectUrl$pkceLabel" }
        }

        config?.providers?.filterIsInstance<HeaderInjectionProviderConfig>()?.forEach { provider ->
            logger.info { "Registered header-injection provider '${provider.name}' — user header: ${provider.userHeader}" }
        }
    }
}

/**
 * Resolve authorization and token endpoint URLs for an OIDC provider, either from the
 * discovery document or from hard-coded values for providers without a standard discovery
 * endpoint (GitHub).
 *
 * The OIDC discovery fetch is performed synchronously during application startup so that
 * any unreachable IdP surfaces as an immediate startup failure rather than a runtime error.
 */
private fun resolveEndpoints(
    provider: OidcProviderConfig,
    discoveryService: OidcDiscoveryService,
): Pair<String, String> {
    if (provider.preset == SsoProviderPreset.GITHUB) {
        return OidcDiscoveryService.GITHUB_AUTHORIZATION_URL to OidcDiscoveryService.GITHUB_TOKEN_URL
    }

    val doc = runBlocking { discoveryService.discover(provider) }
    return doc.authorizationEndpoint to doc.tokenEndpoint
}

/**
 * End-to-end timeout for a single OAuth token-exchange POST. Chosen large enough to
 * absorb a slow IdP under modest load (TLS handshake + multiple round trips for a
 * federated provider can easily span several seconds) but tight enough that a
 * pathologically slow / hung IdP cannot pin a request thread indefinitely. A hostile
 * or partitioned IdP that took longer than this would let the request queue grow
 * unboundedly, exhausting the CIO connection pool — exactly the availability hit
 * L-4 closes.
 */
private const val OAUTH_REQUEST_TIMEOUT_MS = 10_000L

/** TCP connect timeout for the OAuth token-exchange POST. Lower than the overall
 * request timeout because connect-stage stalls are a different failure mode (DNS
 * black-hole, dropped SYNs) that should fail fast and try the next configured IdP
 * (or surface to the user) rather than tie up resources. */
private const val OAUTH_CONNECT_TIMEOUT_MS = 5_000L

/** Idle-socket timeout for the OAuth token-exchange POST. Mirrors
 * [OAUTH_REQUEST_TIMEOUT_MS] because the OAuth POST is a single request/response
 * round-trip with no streaming — the request-level cap is the meaningful one and
 * the socket timeout exists for symmetry with the connect timeout. */
private const val OAUTH_SOCKET_TIMEOUT_MS = 10_000L


