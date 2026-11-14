package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.LOGIN_FLOW_TTL
import cz.pizavo.omnisign.auth.LoginRequest
import cz.pizavo.omnisign.auth.LoginRequestStore
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.PkceChallenge
import cz.pizavo.omnisign.auth.PkceService
import cz.pizavo.omnisign.auth.requireAssumableEmailVerification
import cz.pizavo.omnisign.config.AuthConfig
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
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
 *    [OidcProviderConfig.pkce] is `true` (the default), binding the authorization code to
 *    the login that requested it ([PkceService] explains why a confidential client needs
 *    that). Three of Ktor's hooks share the work, keyed by the OAuth `state`:
 *    - `onStateCreated` runs on the redirect leg as soon as Ktor has generated the `state`.
 *      It stores a fresh verifier, keeps the matching challenge in the call's attributes, and
 *      parks any [LoginRequest] the caller asked for into [LoginRequestStore]. It suspends,
 *      so these database writes do not hold a call thread.
 *    - `authorizeUrlInterceptor` appends that challenge to the authorize URL as
 *      `code_challenge` + `code_challenge_method=S256`.
 *    - `extraTokenParametersProvider` runs on the callback leg only after Ktor has verified
 *      the `state`, and takes the verifier for the token-exchange POST. A forged or expired
 *      `state` therefore spends no verifier. A valid `state` with no verifier left (one used
 *      already, which the stateless nonce manager cannot tell, or one issued before a reboot,
 *      see [serverModule][cz.pizavo.omnisign.di.serverModule]) is refused rather than exchanged
 *      without one, since an identity provider accepts a code requested without a challenge
 *      when no verifier comes with it. The hook can only refuse by throwing; Ktor answers the
 *      callback `401`, as it does for an expired `state`.
 *
 *    The `state`, the verifier and the parked request all live for [LOGIN_FLOW_TTL].
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
                settings = OAuthServerSettings.OAuth2ServerSettings(
                    name = provider.name,
                    authorizeUrl = authUrl,
                    accessTokenUrl = tokenUrl,
                    clientId = provider.clientId,
                    clientSecret = clientSecret.value,
                    requestMethod = io.ktor.http.HttpMethod.Post,
                    defaultScopes = provider.scopes,
                    nonceManager = oauthNonceManager,
                    onStateCreated = { call, state ->
                        if (provider.pkce) call.attributes.put(PKCE_CHALLENGE, pkceService.begin(state))
                        call.requestedLoginHandoff()?.let { loginRequestStore.put(state, it, LOGIN_FLOW_TTL) }
                    },
                    authorizeUrlInterceptor = { request ->
                        request.call.attributes.getOrNull(PKCE_CHALLENGE)?.let { challenge ->
                            parameters.append("code_challenge", challenge.challenge)
                            parameters.append("code_challenge_method", challenge.method)
                        }
                    },
                    extraTokenParametersProvider = { _, callback ->
                        if (provider.pkce) {
                            val verifier = pkceService.consume(callback.state) ?: run {
                                logger.warn {
                                    "Refused a login callback for provider '${provider.name}': its state is " +
                                        "valid but has no PKCE verifier left, so it was used already, or issued " +
                                        "before a reboot or by another server. The code was not exchanged."
                                }
                                error("No PKCE verifier is left for this state")
                            }
                            listOf("code_verifier" to verifier)
                        } else {
                            emptyList()
                        }
                    },
                )
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
 * Read whenever Ktor starts a login, which in practice is the redirect leg: the identity
 * provider's callback carries `code` and `state` and nothing this server sent it.
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
 * Call attribute in which `onStateCreated` hands the authorize URL interceptor the challenge for
 * the verifier it has just stored.
 */
private val PKCE_CHALLENGE = AttributeKey<PkceChallenge>("OmniSignPkceChallenge")

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
 * Resolve authorization and token endpoint URLs for an OIDC provider, either from the
 * discovery document or from hard-coded values for providers without a standard discovery
 * endpoint (GitHub).
 *
 * The OIDC discovery fetch is performed synchronously during application startup so that
 * any unreachable IdP surfaces as an immediate startup failure rather than a runtime error.
 * The fetched document is also where [requireAssumableEmailVerification] refuses a provider
 * that assumes its addresses verified on one of Entra ID's multi-tenant endpoints.
 *
 * @throws IllegalArgumentException when [provider] assumes verification on such an endpoint.
 */
private fun resolveEndpoints(
    provider: OidcProviderConfig,
    discoveryService: OidcDiscoveryService,
): Pair<String, String> {
    if (provider.preset == SsoProviderPreset.GITHUB) {
        return OidcDiscoveryService.GITHUB_AUTHORIZATION_URL to OidcDiscoveryService.GITHUB_TOKEN_URL
    }

    val doc = runBlocking { discoveryService.discover(provider) }
    requireAssumableEmailVerification(provider, doc)
    return doc.authorizationEndpoint to doc.tokenEndpoint
}


