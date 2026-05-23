package cz.pizavo.omnisign.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import cz.pizavo.omnisign.config.OidcProviderConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 *    ES256/ES384/ES512. `none` and HMAC variants are rejected.
 * 2. **`iss` claim** — must equal [OidcDiscoveryDocument.issuer] (the IdP's canonical
 *    issuer URL declared in its discovery document).
 * 3. **`aud` claim** — must contain [OidcProviderConfig.clientId].
 * 4. **`exp` claim** — must be in the future (30 seconds of clock skew allowed).
 * 5. **`sub` claim** — must be present; returned to the caller for cross-checking
 *    against the UserInfo `sub` so any tampering of the UserInfo response is detected.
 *
 * JWKS fetches are cached and rate-limited per provider via [JwkProviderBuilder]:
 * up to 10 keys held in memory for 1 hour, fetched at most 10 times per minute. Keys
 * roll naturally as IdPs rotate signing keys without operator intervention.
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

    private val jwkProviders = ConcurrentHashMap<String, JwkProvider>()

    /**
     * Verify the id_token returned by [provider]'s authorization-code callback.
     *
     * @param provider OIDC provider configuration. Used to look up the discovery
     *   document and the expected audience ([OidcProviderConfig.clientId]).
     * @param idTokenString The raw `id_token` JWT string from
     *   `OAuthAccessTokenResponse.OAuth2.extraParameters["id_token"]`.
     * @return The verified id_token's [sub claim][VerifiedIdToken.subject] and any
     *   `email` claim, for downstream cross-checking against UserInfo.
     * @throws IdTokenVerificationException on any verification failure. Each subtype
     *   identifies a specific failure mode; see [IdTokenVerificationException].
     */
    suspend fun verify(provider: OidcProviderConfig, idTokenString: String): VerifiedIdToken {
        val discovery = discoveryService.discover(provider)
        val jwkProvider = jwkProviders.computeIfAbsent(provider.name) {
            jwkProviderFactory(discovery.jwksUri)
        }

        return withContext(Dispatchers.IO) {
            verifyBlocking(
                idTokenString = idTokenString,
                jwkProvider = jwkProvider,
                expectedIssuer = discovery.issuer,
                expectedAudience = provider.clientId,
            )
        }
    }

    /**
     * Blocking verification core. Called from [verify] inside [Dispatchers.IO] because
     * the underlying JWKS fetch and JWT verification calls are synchronous.
     */
    private fun verifyBlocking(
        idTokenString: String,
        jwkProvider: JwkProvider,
        expectedIssuer: String,
        expectedAudience: String,
    ): VerifiedIdToken {
        val decoded = try {
            JWT.decode(idTokenString)
        } catch (e: JWTDecodeException) {
            throw IdTokenVerificationException.Malformed(e)
        }

        val kid = decoded.keyId ?: throw IdTokenVerificationException.MissingKid()

        val publicKey = try {
            jwkProvider.get(kid).publicKey
        } catch (e: JwkException) {
            throw IdTokenVerificationException.KeyNotFound(kid, e)
        }

        val algorithm = algorithmFor(decoded.algorithm, publicKey)

        try {
            JWT.require(algorithm)
                .withIssuer(expectedIssuer)
                .withAudience(expectedAudience)
                .acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
                .build()
                .verify(idTokenString)
        } catch (e: JWTVerificationException) {
            throw IdTokenVerificationException.VerificationFailed(
                "id_token failed verification: ${e.message ?: e::class.simpleName}",
                e,
            )
        }

        val subject = decoded.subject
            ?: throw IdTokenVerificationException.ClaimInvalid("sub", "claim is missing")
        val email = decoded.getClaim("email").asString()

        logger.debug { "Verified id_token for sub='$subject' (iss=$expectedIssuer)" }
        return VerifiedIdToken(subject = subject, email = email, issuer = expectedIssuer)
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

        /** Default cache size for [JwkProviderBuilder]. */
        private const val JWKS_CACHE_SIZE = 10L

        /** Default cache TTL for [JwkProviderBuilder]. */
        private const val JWKS_CACHE_TTL_HOURS = 1L

        /** Default rate-limit bucket size for [JwkProviderBuilder]. */
        private const val JWKS_RATE_LIMIT_BUCKET = 10L

        /** Default rate-limit refill period for [JwkProviderBuilder]. */
        private const val JWKS_RATE_LIMIT_PER_MINUTE = 1L

        /**
         * Construct the default cached + rate-limited [JwkProvider] for a given JWKS URL.
         *
         * Up to [JWKS_CACHE_SIZE] keys are held in memory for [JWKS_CACHE_TTL_HOURS]
         * hour(s); fetches that miss the cache are throttled to at most
         * [JWKS_RATE_LIMIT_BUCKET] per [JWKS_RATE_LIMIT_PER_MINUTE] minute(s) so a
         * malformed token referencing an unknown `kid` cannot trigger a DoS against
         * the IdP's JWKS endpoint.
         */
        fun defaultJwkProvider(jwksUri: String): JwkProvider =
            JwkProviderBuilder(URL(jwksUri))
                .cached(JWKS_CACHE_SIZE, JWKS_CACHE_TTL_HOURS, TimeUnit.HOURS)
                .rateLimited(JWKS_RATE_LIMIT_BUCKET, JWKS_RATE_LIMIT_PER_MINUTE, TimeUnit.MINUTES)
                .build()
    }
}

/**
 * Outcome of a successful [IdTokenVerifier.verify] call.
 *
 * Only the fields needed by the OIDC callback handler are exposed here. The full set of
 * id_token claims is intentionally not propagated — UserInfo remains the source of
 * truth for rich identity claims, and the id_token only contributes the cryptographic
 * `sub` binding plus a convenience `email` for environments where UserInfo is sparse.
 *
 * @property subject The id_token `sub` claim. Cross-checked against the UserInfo
 *   `sub` claim in the route handler so any UserInfo substitution is detected.
 * @property email The id_token `email` claim, if present. Optional in OIDC; many
 *   providers only emit it via UserInfo.
 * @property issuer The id_token `iss` claim (already verified to equal the
 *   discovery document's issuer). Echoed back to the caller for logging.
 */
data class VerifiedIdToken(
    val subject: String,
    val email: String?,
    val issuer: String,
)
