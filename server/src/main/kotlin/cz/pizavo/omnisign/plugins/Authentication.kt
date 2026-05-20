package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.HeaderInjectionProviderConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
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

            oauth(authName) {
                urlProvider = { redirectUrl }
                providerLookup = {
                    OAuthServerSettings.OAuth2ServerSettings(
                        name = provider.name,
                        authorizeUrl = authUrl,
                        accessTokenUrl = tokenUrl,
                        clientId = provider.clientId,
                        clientSecret = provider.clientSecret,
                        requestMethod = io.ktor.http.HttpMethod.Post,
                        defaultScopes = provider.scopes,
                        nonceManager = oauthNonceManager,
                    )
                }
                client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
            }

            logger.info { "Registered OIDC provider '${provider.name}' (${provider.displayName}) — redirect: $redirectUrl" }
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


