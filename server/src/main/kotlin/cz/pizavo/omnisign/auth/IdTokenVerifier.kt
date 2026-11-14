package cz.pizavo.omnisign.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Verifies the OIDC `id_token` returned by an IdP alongside the access token on the
 * authorization-code callback.
 *
 * Defense in depth over the bare OAuth2 flow: even though OmniSign is a confidential
 * client (so a stolen authorization code is mostly inert without the client secret),
 * verifying the id_token cryptographically rather than blindly trusting whatever the
 * UserInfo endpoint returns means the IdP's signing key is the root of trust for
 * identity assertions. If an attacker proxies, caches, or substitutes the UserInfo
 * response (network MITM on the IdP side, malicious upstream HTTP cache, IdP
 * misconfiguration that exposes UserInfo without auth) the id_token still pins the
 * `sub` claim to whatever the IdP cryptographically signed.
 *
 * Verification steps (per [OpenID Connect Core §3.1.3.7](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation)):
 *
 * 1. **Signature** — id_token's `kid` header is looked up in the IdP's JWKS
 *    (URL from [OidcDiscoveryDocument.jwksUri]) and the resulting public key is used
 *    to verify the signature. Supported algorithms: RS256/RS384/RS512 and
 *    ES256/ES384/ES512. `none` and HMAC variants are rejected. A key whose JWKS entry
 *    names an `issuer`, as Entra ID's do, signs only for that issuer.
 * 2. **`iss` claim** — must equal [OidcDiscoveryDocument.issuer] (the IdP's canonical
 *    issuer URL declared in its discovery document). Entra ID's multi-tenant documents
 *    (`common`, `organizations`) declare the template
 *    `https://login.microsoftonline.com/{tenantid}/v2.0` instead; the token's `tid` claim,
 *    which must be a GUID, takes the placeholder's place, so the token must name its own
 *    tenant as its issuer.
 * 3. **`aud` claim** — must contain [OidcProviderConfig.clientId]. An `azp` claim, which a
 *    token for several audiences must carry, must name that client too.
 * 4. **`exp` and `iat` claims** — must be present, and `exp` in the future (30 seconds of
 *    clock skew allowed).
 * 5. **`sub` claim** — must be present; returned to the caller for cross-checking
 *    against the UserInfo `sub` so any tampering of the UserInfo response is detected.
 *
 * The token's `email` claim and whether the token marks that address verified are returned
 * too: the `allowedEmailDomains` check prefers the address the IdP signed to UserInfo's (see
 * [VerifiedIdToken.emailVerified] and [withIdToken]).
 *
 * JWKS fetches are cached and rate-limited per JWKS URL via [JwkProviderBuilder]:
 * up to 10 keys held in memory for 1 hour, fetched at most 10 times per minute, each
 * fetch bounded by a 5-second connect and a 10-second read timeout. Keys roll naturally
 * as IdPs rotate signing keys without operator intervention, and a discovery document
 * that moves its `jwks_uri` is followed once [OidcDiscoveryService] refreshes it.
 *
 * @param discoveryService Used to resolve [OidcDiscoveryDocument.jwksUri] and
 *   [OidcDiscoveryDocument.issuer] for each provider on first verification.
 * @param jwkProviderFactory Builds the [JwkProvider] used to fetch the JWKS. Defaults
 *   to a cached, rate-limited provider keyed on the JWKS URL. Override in tests to
 *   inject a stub provider.
 */
class IdTokenVerifier(
    private val discoveryService: OidcDiscoveryService,
    private val jwkProviderFactory: (jwksUri: String) -> JwkProvider = ::defaultJwkProvider,
) {

    /**
     * One [JwkProvider] per JWKS URL, so providers that share a key set share its cache, and
     * a discovery document that moves its `jwks_uri` gets a provider for the new URL.
     */
    private val jwkProviders = ConcurrentHashMap<String, JwkProvider>()

    /**
     * Verify the id_token returned by [provider]'s authorization-code callback.
     *
     * @param provider OIDC provider configuration. Used to look up the discovery
     *   document and the expected audience ([OidcProviderConfig.clientId]).
     * @param idTokenString The raw `id_token` JWT string from
     *   `OAuthAccessTokenResponse.OAuth2.extraParameters["id_token"]`.
     * @return The verified id_token's [sub claim][VerifiedIdToken.subject], for
     *   cross-checking against UserInfo, and any `email` claim with whether the token marks
     *   it verified.
     * @throws IdTokenVerificationException on any verification failure. Each subtype
     *   identifies a specific failure mode; see [IdTokenVerificationException].
     */
    suspend fun verify(provider: OidcProviderConfig, idTokenString: String): VerifiedIdToken {
        val discovery = discoveryService.discover(provider)
        val jwkProvider = jwkProviders.computeIfAbsent(discovery.jwksUri) { jwkProviderFactory(it) }

        return withContext(Dispatchers.IO) {
            verifyBlocking(
                idTokenString = idTokenString,
                jwkProvider = jwkProvider,
                discoveryIssuer = discovery.issuer,
                expectedAudience = provider.clientId,
                preset = provider.preset,
            )
        }
    }

    /**
     * Blocking verification core. Called from [verify] inside [Dispatchers.IO] because
     * the underlying JWKS fetch and JWT verification calls are synchronous.
     *
     * The issuer the token must name is worked out before the signature is checked, from
     * the token's own `tid` when [discoveryIssuer] is Entra ID's tenant template. The check
     * then covers that `tid` too, so a token that names a tenant it was not signed for fails.
     *
     * Of two flags that disagree about the address, the one that says it is not verified
     * wins, so a token never vouches for more than every flag in it allows.
     *
     * @param discoveryIssuer The discovery document's `issuer`, possibly Entra ID's
     *   `{tenantid}` template.
     * @param preset The provider's preset; only a [SsoProviderPreset.MICROSOFT] token's
     *   `xms_edov` claim is read, since other IdPs give the name no meaning.
     */
    private fun verifyBlocking(
        idTokenString: String,
        jwkProvider: JwkProvider,
        discoveryIssuer: String,
        expectedAudience: String,
        preset: SsoProviderPreset?,
    ): VerifiedIdToken {
        val decoded = try {
            JWT.decode(idTokenString)
        } catch (e: JWTDecodeException) {
            throw IdTokenVerificationException.Malformed(e)
        }

        val kid = decoded.keyId ?: throw IdTokenVerificationException.MissingKid()

        val jwk = try {
            jwkProvider.get(kid)
        } catch (e: JwkException) {
            throw IdTokenVerificationException.KeyNotFound(kid, e)
        }
        val publicKey = try {
            jwk.publicKey
        } catch (e: JwkException) {
            throw IdTokenVerificationException.KeyNotFound(kid, e)
        }

        val algorithm = algorithmFor(decoded.algorithm, publicKey)
        val expectedIssuer = withTenantOf(decoded, discoveryIssuer)

        try {
            JWT.require(algorithm)
                .withIssuer(expectedIssuer)
                .withAudience(expectedAudience)
                .withClaimPresence("exp")
                .withClaimPresence("iat")
                .acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
                .build()
                .verify(idTokenString)
        } catch (e: JWTVerificationException) {
            throw IdTokenVerificationException.VerificationFailed(
                "id_token failed verification: ${e.message ?: e::class.simpleName}",
                e,
            )
        }

        requireKeyIssuedFor(jwk, decoded)
        requireAuthorizedParty(decoded, expectedAudience)

        val subject = decoded.subject
            ?: throw IdTokenVerificationException.ClaimInvalid("sub", "claim is missing")
        val email = decoded.getClaim("email").asString()
        val domainOwnerVerified = if (preset == SsoProviderPreset.MICROSOFT) {
            decoded.getClaim("xms_edov").asVerificationFlag()
        } else {
            null
        }
        val emailVerified = listOfNotNull(
            decoded.getClaim("email_verified").asVerificationFlag(),
            domainOwnerVerified,
        ).minOrNull()

        logger.debug { "Verified id_token for sub='$subject' (iss=$expectedIssuer)" }
        return VerifiedIdToken(
            subject = subject,
            email = email,
            issuer = expectedIssuer,
            emailVerified = emailVerified,
        )
    }

    /**
     * Fill Entra ID's tenant placeholder in [issuer] with the tenant [decoded] names.
     *
     * Entra ID's multi-tenant discovery documents (`common`, `organizations`), and the keys
     * they publish, name the issuer `https://login.microsoftonline.com/{tenantid}/v2.0`, where
     * the placeholder stands for the tenant of whoever signs in. As Microsoft's token
     * validation guide asks, the token's `tid` claim takes the placeholder's place, and it
     * must be a GUID, so it cannot reshape the URL. Any other issuer comes back unchanged.
     *
     * @param decoded The token, whose `tid` claim names its tenant.
     * @param issuer An issuer from a discovery document or a JWKS entry.
     * @return [issuer] with its placeholder filled in.
     * @throws IdTokenVerificationException.ClaimInvalid when [issuer] holds the placeholder
     *   and the token's `tid` is missing or is no GUID.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun withTenantOf(decoded: DecodedJWT, issuer: String): String {
        if (!issuer.contains(TENANT_ID_PLACEHOLDER)) return issuer
        val tenantId = decoded.getClaim("tid").asString()
            ?: throw IdTokenVerificationException.ClaimInvalid("tid", "claim is missing")
        if (Uuid.parseHexDashOrNull(tenantId) == null) {
            throw IdTokenVerificationException.ClaimInvalid("tid", "not a GUID")
        }
        return issuer.replace(TENANT_ID_PLACEHOLDER, tenantId)
    }

    /**
     * Refuse a token signed with a key that its JWKS keeps for another issuer.
     *
     * Entra ID's multi-tenant key sets mix keys for organisations' tenants, whose `issuer` is
     * the `{tenantid}` template, with keys for personal Microsoft accounts, whose `issuer`
     * names that one tenant. Microsoft asks relying parties to hold each key to its issuer,
     * so that a key published for one kind of account cannot vouch for the other. A key
     * whose entry names no issuer, as most IdPs publish them, is not restricted.
     *
     * @param jwk The JWKS entry whose key verified the signature.
     * @param decoded The verified token.
     * @throws IdTokenVerificationException.ClaimInvalid when the key's issuer, its
     *   placeholder filled with the token's tenant, is not the token's `iss`.
     */
    private fun requireKeyIssuedFor(jwk: Jwk, decoded: DecodedJWT) {
        val keyIssuer = jwk.additionalAttributes?.get(JWK_ISSUER_ATTRIBUTE) as? String ?: return
        if (withTenantOf(decoded, keyIssuer) != decoded.issuer) {
            throw IdTokenVerificationException.ClaimInvalid(
                "iss",
                "the signing key '${jwk.id}' signs only for the issuer '$keyIssuer'",
            )
        }
    }

    /**
     * Check the `azp` (authorized party) claim as OpenID Connect Core §3.1.3.7 asks: a token
     * for several audiences must carry it, and when present it must name this client.
     *
     * @param decoded The verified token.
     * @param clientId This server's client ID at the IdP.
     * @throws IdTokenVerificationException.ClaimInvalid when `azp` names another client, or is
     *   missing from a token for several audiences.
     */
    private fun requireAuthorizedParty(decoded: DecodedJWT, clientId: String) {
        val authorizedParty = decoded.getClaim("azp").asString()
        if (authorizedParty == null) {
            if (decoded.audience.orEmpty().size > 1) {
                throw IdTokenVerificationException.ClaimInvalid(
                    "azp",
                    "claim is missing from a token for several audiences",
                )
            }
        } else if (authorizedParty != clientId) {
            throw IdTokenVerificationException.ClaimInvalid("azp", "names another client")
        }
    }

    /**
     * Build the Auth0 [Algorithm] instance matching the JWS `alg` header value, paired
     * with the public key resolved from JWKS.
     *
     * Only RSA and ECDSA families are accepted. HMAC variants are inappropriate for
     * cross-party signing (would require a shared secret with the IdP) and `none` is
     * a well-known JWT-attack vector.
     */
    private fun algorithmFor(algName: String, publicKey: PublicKey): Algorithm = when (algName) {
        "RS256" -> Algorithm.RSA256(publicKey as RSAPublicKey, null)
        "RS384" -> Algorithm.RSA384(publicKey as RSAPublicKey, null)
        "RS512" -> Algorithm.RSA512(publicKey as RSAPublicKey, null)
        "ES256" -> Algorithm.ECDSA256(publicKey as ECPublicKey, null)
        "ES384" -> Algorithm.ECDSA384(publicKey as ECPublicKey, null)
        "ES512" -> Algorithm.ECDSA512(publicKey as ECPublicKey, null)
        else -> throw IdTokenVerificationException.UnsupportedAlgorithm(algName)
    }

    companion object {
        /**
         * Allowed clock skew, in seconds, when validating `exp` / `nbf` / `iat`. 30
         * seconds is a conservative value that accommodates ordinary NTP drift between
         * IdPs and OmniSign without opening a meaningful replay window.
         */
        const val CLOCK_SKEW_LEEWAY_SECONDS = 30L

        /**
         * The placeholder Entra ID's multi-tenant discovery documents and keys put in an
         * issuer for the tenant of whoever signs in, spelled as they spell it.
         */
        private const val TENANT_ID_PLACEHOLDER = "{tenantid}"

        /** The JWKS entry member in which Entra ID names the issuer a key signs for. */
        private const val JWK_ISSUER_ATTRIBUTE = "issuer"

        /** Default cache size for [JwkProviderBuilder]. */
        private const val JWKS_CACHE_SIZE = 10L

        /** Default cache TTL for [JwkProviderBuilder]. */
        private const val JWKS_CACHE_TTL_HOURS = 1L

        /** Default rate-limit bucket size for [JwkProviderBuilder]. */
        private const val JWKS_RATE_LIMIT_BUCKET = 10L

        /** Default rate-limit refill period for [JwkProviderBuilder]. */
        private const val JWKS_RATE_LIMIT_PER_MINUTE = 1L

        /**
         * Default connect timeout, in milliseconds, of a JWKS fetch. Matches the connect
         * timeout of the HTTP client that serves the other IdP calls (see
         * [cz.pizavo.omnisign.di.serverModule]).
         */
        private const val JWKS_CONNECT_TIMEOUT_MS = 5_000

        /**
         * Default read timeout, in milliseconds, of a JWKS fetch: how long the response may
         * stall before the fetch gives up. Matches the socket timeout of the HTTP client that
         * serves the other IdP calls (see [cz.pizavo.omnisign.di.serverModule]).
         */
        private const val JWKS_READ_TIMEOUT_MS = 10_000

        /**
         * Construct the default cached + rate-limited [JwkProvider] for a given JWKS URL.
         *
         * Up to [JWKS_CACHE_SIZE] keys are held in memory for [JWKS_CACHE_TTL_HOURS]
         * hour(s); fetches that miss the cache are throttled to at most
         * [JWKS_RATE_LIMIT_BUCKET] per [JWKS_RATE_LIMIT_PER_MINUTE] minute(s) so a
         * malformed token referencing an unknown `kid` cannot trigger a DoS against
         * the IdP's JWKS endpoint.
         *
         * A fetch that cannot connect within [connectTimeoutMillis], or whose response
         * stalls for [readTimeoutMillis], fails with a [JwkException], which
         * [IdTokenVerifier.verify] reports as [IdTokenVerificationException.KeyNotFound].
         * Without these bounds `URLConnection` waits indefinitely, so an IdP that accepts
         * the connection but never answers would hold the login request, and the thread
         * verifying it, forever.
         *
         * @param jwksUri The discovery document's `jwks_uri`.
         * @param connectTimeoutMillis Connect timeout of a JWKS fetch, in milliseconds.
         * @param readTimeoutMillis Read timeout of a JWKS fetch, in milliseconds.
         */
        fun defaultJwkProvider(
            jwksUri: String,
            connectTimeoutMillis: Int = JWKS_CONNECT_TIMEOUT_MS,
            readTimeoutMillis: Int = JWKS_READ_TIMEOUT_MS,
        ): JwkProvider =
            JwkProviderBuilder(URI(jwksUri).toURL())
                .cached(JWKS_CACHE_SIZE, JWKS_CACHE_TTL_HOURS, TimeUnit.HOURS)
                .rateLimited(JWKS_RATE_LIMIT_BUCKET, JWKS_RATE_LIMIT_PER_MINUTE, TimeUnit.MINUTES)
                .timeouts(connectTimeoutMillis, readTimeoutMillis)
                .build()
    }
}

/**
 * Read this claim as a verification flag: a JSON boolean, or the string `"true"` or `"false"`
 * in any case, as Amazon Cognito and Sign in with Apple send it.
 *
 * A claim that is present but none of these, such as `0` or `"no"`, counts as `false`: the
 * IdP said something about the address, and only a recognisable yes may vouch for it.
 *
 * @return The flag, or `null` when the claim is absent or JSON `null`.
 */
private fun Claim.asVerificationFlag(): Boolean? {
    if (isMissing || isNull) return null
    return asBoolean() ?: asString()?.lowercase()?.toBooleanStrictOrNull() ?: false
}
