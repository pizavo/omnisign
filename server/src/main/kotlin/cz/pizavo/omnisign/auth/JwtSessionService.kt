package cz.pizavo.omnisign.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.config.isSymmetric
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Issues and verifies HMAC-signed JWT session tokens for authenticated users.
 *
 * The signing algorithm is selected from [SessionConfig.algorithm]. All three HMAC
 * variants ([JwtAlgorithmType.HS256], [JwtAlgorithmType.HS384], [JwtAlgorithmType.HS512])
 * are supported; asymmetric algorithms (RS*, ES*) are stubbed in [JwtAlgorithmType] for
 * future implementation and will throw [UnsupportedOperationException] if selected.
 *
 * Tokens embed the [AuthenticatedPrincipal] fields as standard JWT claims and are
 * validated on each API request by the Ktor `bearer` authentication provider.
 *
 * **Null-safe by design.** When [secret] is `null` the service is "inert" — [verify]
 * returns `null` for every token (no valid tokens exist) and [issue] throws. This is
 * the state when authentication is disabled
 * ([cz.pizavo.omnisign.config.AuthConfig.enabled] = `false`): the bearer authenticator
 * stays registered so routes that always reference it (e.g. `/auth/session`) work, but
 * no signing material exists. The fail-closed verify keeps a 401 response shape for
 * any Bearer header that happens to arrive when auth is off.
 *
 * @param config JWT session configuration (algorithm, issuer, audience, expiry).
 * @param secret HMAC signing secret resolved from the `OMNISIGN_JWT_SECRET`
 *   environment variable at server startup, or `null` when auth is disabled. See
 *   [cz.pizavo.omnisign.config.ServerSecrets] for the resolution rules.
 */
class JwtSessionService(
    private val config: SessionConfig,
    secret: Sensitive<String>?,
) {

    private val algorithm: Algorithm? = secret?.let {
        buildAlgorithm(config.algorithm, it.value)
    }

    /**
     * Issue a signed JWT for the given [principal].
     *
     * @param principal Authenticated user to embed in the token.
     * @return Signed JWT string.
     * @throws IllegalStateException if no signing secret is configured. Reaching this is a
     *   programming error: `issue` should only be invoked from auth-issuing routes
     *   (`/auth/callback/{name}`, `/auth/refresh`) that are only registered or reachable
     *   when authentication is enabled and a secret is therefore present.
     */
    fun issue(principal: AuthenticatedPrincipal): String {
        val alg = checkNotNull(algorithm) {
            "JwtSessionService.issue called but no signing secret is configured " +
                "(OMNISIGN_JWT_SECRET is unset, typically because auth.enabled is false). " +
                "Issue should not be reachable in this state — check route gating."
        }
        val now = Date()
        val expiry = Date(now.time + config.tokenExpirySeconds * 1_000L)

        val builder = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(principal.userId)
            .withClaim(CLAIM_PROVIDER, principal.providerName)
            .withClaim(CLAIM_AUTH_TIME, principal.authTime.epochSeconds)
            .withIssuedAt(now)
            .withExpiresAt(expiry)
        if (principal.email != null) builder.withClaim(CLAIM_EMAIL, principal.email)
        if (principal.displayName != null) builder.withClaim(CLAIM_DISPLAY_NAME, principal.displayName)
        return builder.sign(alg)
    }

    /**
     * Verify and decode a JWT string into an [AuthenticatedPrincipal].
     *
     * Returns `null` when no signing secret is configured (auth disabled), making this
     * service safe to wire into the bearer authenticator unconditionally — any Bearer
     * token that arrives in an auth-disabled deployment fails verification and the
     * caller sees a 401.
     *
     * @param token Raw JWT string from the `Authorization: Bearer` header.
     * @return The decoded [AuthenticatedPrincipal], or `null` if the token is invalid,
     *   expired, tampered with, or auth is disabled (no secret configured).
     */
    fun verify(token: String): AuthenticatedPrincipal? {
        val alg = algorithm ?: return null
        return try {
            val verifier = JWT.require(alg)
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .build()

            val decoded = verifier.verify(token)
            val authTimeSeconds = decoded.getClaim(CLAIM_AUTH_TIME).asLong()
                ?: decoded.issuedAt?.toInstant()?.epochSecond
                ?: return null
            AuthenticatedPrincipal(
                userId = decoded.subject,
                email = decoded.getClaim(CLAIM_EMAIL).asString(),
                displayName = decoded.getClaim(CLAIM_DISPLAY_NAME).asString(),
                providerName = decoded.getClaim(CLAIM_PROVIDER).asString(),
                authTime = Instant.fromEpochSeconds(authTimeSeconds),
            )
        } catch (ex: JWTVerificationException) {
            logger.debug { "JWT verification failed: ${ex.message}" }
            null
        }
    }

    companion object {
        private const val CLAIM_EMAIL = "email"
        private const val CLAIM_DISPLAY_NAME = "displayName"
        private const val CLAIM_PROVIDER = "provider"

        /**
         * Standard JWT/OIDC claim name for the original SSO authentication time.
         *
         * Preserved verbatim across `/auth/refresh` cycles so the refresh route can bound
         * the overall session lifetime via [SessionConfig.maxSessionSeconds]. Defined in
         * OpenID Connect Core 1.0 §2 and JWT RFC 7519 §5.
         */
        const val CLAIM_AUTH_TIME = "auth_time"

        /** Ktor authentication provider name used for Bearer JWT validation on API routes. */
        const val AUTH_NAME_JWT = "jwt-api"

        /** Prefix for per-provider OIDC OAuth2 authentication provider names. */
        const val AUTH_NAME_OIDC_PREFIX = "oidc-"
    }
}

/**
 * Build the [Algorithm] instance from [algorithmType] and [secret].
 *
 * Only HMAC variants are implemented at present. Asymmetric types (RS*, ES*) are
 * defined in [JwtAlgorithmType] as extension points and will be implemented when a
 * multiservice deployment scenario requires them.
 *
 * @throws UnsupportedOperationException if an asymmetric algorithm is selected.
 */
private fun buildAlgorithm(algorithmType: JwtAlgorithmType, secret: String): Algorithm {
    require(algorithmType.isSymmetric) {
        "Algorithm $algorithmType is an asymmetric key-pair type that is not yet " +
            "implemented. Use HS256, HS384, or HS512 with a shared secret for now."
    }
    return when (algorithmType) {
        JwtAlgorithmType.HS256 -> Algorithm.HMAC256(secret)
        JwtAlgorithmType.HS384 -> Algorithm.HMAC384(secret)
        JwtAlgorithmType.HS512 -> Algorithm.HMAC512(secret)
        else -> error("Unreachable — isSymmetric guard above covers all non-HMAC variants")
    }
}
