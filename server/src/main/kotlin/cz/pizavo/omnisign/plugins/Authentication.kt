package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.LoginRequest
import cz.pizavo.omnisign.auth.LoginRequestStore
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.PkceService
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.util.NonceManager
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
 *    providers back **both** legs of the authorization-code flow: `/auth/redirect/{name}`
 *    (where the provider issues the `302` challenge to the IdP) and `/auth/callback/{name}`
 *    (where it exchanges the returned `code` for tokens and produces the
 *    [OAuthAccessTokenResponse.OAuth2] principal). Both routes must therefore live inside
 *    the same `authenticate("oidc-{name}") { }` block — Ktor only runs the OAuth
 *    interceptor for routes enclosed by it, so a callback mounted outside would never
 *    perform the token exchange and would always see a `null` principal.
 *
 *    The token-exchange POST uses the shared [HttpClient] from
 *    [serverModule][cz.pizavo.omnisign.di.serverModule] — the same client the discovery,
 *    UserInfo, and id_token-verification services use. Injecting it (rather than
 *    constructing one here) keeps a single configured outbound client for all IdP traffic
 *    and lets tests substitute a `MockEngine` for the IdP.
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
 *    Finally, `authorizeUrlInterceptor` parks any [LoginRequest] the caller asked for into
 *    [LoginRequestStore], keyed by that same `state`. This is the only hook that can: the
 *    query parameters are readable from `providerLookup`'s [ApplicationCall] receiver but
 *    the `state` does not exist yet, and by the time the callback route handler runs, Ktor
 *    has been through `providerLookup` again — where the PKCE row keyed by the same `state`
 *    is consumed. Reading the parameters in `providerLookup` and writing them in the
 *    interceptor closure it returns is what gets both halves into one place.
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
    val httpClient by inject<HttpClient>()
    val loginRequestStore by inject<LoginRequestStore>()

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

                    val requestedHandoff = requestedLoginHandoff()

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
                            val state by lazy {
                                parameters["state"]
                                    ?: error(
                                        "Ktor OAuth should have appended `state` to the authorize URL " +
                                            "before authorizeUrlInterceptor runs — got null for provider '${provider.name}'",
                                    )
                            }
                            if (provider.pkce) {
                                val challenge = runBlocking { pkceService.begin(state) }
                                parameters.append("code_challenge", challenge.challenge)
                                parameters.append("code_challenge_method", challenge.method)
                            }
                            requestedHandoff?.let {
                                runBlocking { loginRequestStore.put(state, it, LOGIN_REQUEST_TTL) }
                            }
                        },
                    )
                }
                client = httpClient
            }

            val pkceLabel = if (provider.pkce) " — PKCE enabled" else " — PKCE disabled"
            logger.info { "Registered OIDC provider '${provider.name}' (${provider.displayName}) — redirect: $redirectUrl$pkceLabel" }
        }
    }
}

/**
 * Read the hand-off parameters a single-page app appends to `GET /auth/redirect/{provider}`.
 *
 * Both are required together. Answering `null` when only one is present is what makes the
 * omission visible: the login completes and the callback replies with token JSON instead of
 * redirecting, so a client that forgets the challenge lands on a page of JSON rather than
 * silently receiving an unbound hand-off code. There is no configuration under which half a
 * hand-off is a thing to honour.
 *
 * Called on both legs of the flow, but only ever finds anything on the redirect leg: the
 * identity provider's callback carries `code` and `state` and nothing this server sent it.
 *
 * @return The requested hand-off, or `null` when this is not a hand-off login.
 */
private fun ApplicationCall.requestedLoginHandoff(): LoginRequest? {
    val returnTo = request.queryParameters[RETURN_TO_PARAMETER]?.takeIf { it.isNotBlank() } ?: return null
    val challenge = request.queryParameters[HANDOFF_CHALLENGE_PARAMETER]?.takeIf { it.isNotBlank() } ?: return null
    if (returnTo.length > MAX_RETURN_TO_LENGTH || challenge.length > MAX_HANDOFF_CHALLENGE_LENGTH) {
        logger.warn {
            "Ignoring a login hand-off with an over-long returnTo (${returnTo.length} chars) or " +
                "handoffChallenge (${challenge.length} chars); the login will complete without a hand-off. " +
                "A valid challenge is 43 chars and returnTo is one of the configured redirect URIs, so this " +
                "is a malformed client request rather than anything to store — bounding it here keeps an " +
                "oversize value from being rejected by the login_requests column widths on a non-SQLite backend."
        }
        return null
    }
    return LoginRequest(returnTo = returnTo, handoffChallenge = challenge)
}

/**
 * Query parameter naming the page a single-page app wants the finished login returned to.
 */
const val RETURN_TO_PARAMETER: String = "returnTo"

/**
 * Query parameter carrying the app's PKCE `S256` challenge for the hand-off code.
 */
const val HANDOFF_CHALLENGE_PARAMETER: String = "handoffChallenge"

/**
 * Upper bound on the `returnTo` a hand-off request may carry, matching the `login_requests`
 * `return_to` column. A legitimate value is one of the configured (short) redirect URIs;
 * anything longer cannot match the allowlist anyway, so it is dropped before storage.
 */
private const val MAX_RETURN_TO_LENGTH = 2048

/**
 * Upper bound on the `handoffChallenge` a hand-off request may carry, matching the
 * `login_requests` `handoff_challenge` column and RFC 7636's 128-char verifier cap. A valid
 * S256 challenge is 43 characters; anything longer cannot match a real verifier's digest.
 */
private const val MAX_HANDOFF_CHALLENGE_LENGTH = 128

/**
 * How long a parked [LoginRequest] stays claimable.
 *
 * Matches [PkceService]'s default verifier TTL, because it covers the same round trip and
 * expiring first would only produce the more confusing of two failures — a login that
 * completes and then refuses to hand back.
 */
private val LOGIN_REQUEST_TTL: Duration = 5.minutes

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


