package cz.pizavo.omnisign.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.config.isSymmetric
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

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
 * @param config JWT session configuration (algorithm, secret, issuer, audience, expiry).
 */
class JwtSessionService(private val config: SessionConfig) {

    private val algorithm: Algorithm = buildAlgorithm(config)

    /**
     * Issue a signed JWT for the given [principal].
     *
     * @param principal Authenticated user to embed in the token.
     * @return Signed JWT string.
     */
    fun issue(principal: AuthenticatedPrincipal): String {
        val now = Date()
        val expiry = Date(now.time + config.tokenExpirySeconds * 1_000L)

        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(principal.userId)
            .withClaim(CLAIM_EMAIL, principal.email)
            .withClaim(CLAIM_DISPLAY_NAME, principal.displayName)
            .withClaim(CLAIM_PROVIDER, principal.providerName)
            .withIssuedAt(now)
            .withExpiresAt(expiry)
            .sign(algorithm)
    }

    /**
     * Verify and decode a JWT string into an [AuthenticatedPrincipal].
     *
     * @param token Raw JWT string from the `Authorization: Bearer` header.
     * @return The decoded [AuthenticatedPrincipal], or `null` if the token is invalid,
     *   expired, or has been tampered with.
     */
    fun verify(token: String): AuthenticatedPrincipal? {
        return try {
            val verifier = JWT.require(algorithm)
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .build()

            val decoded = verifier.verify(token)
            AuthenticatedPrincipal(
                userId = decoded.subject,
                email = decoded.getClaim(CLAIM_EMAIL).asString(),
                displayName = decoded.getClaim(CLAIM_DISPLAY_NAME).asString(),
                providerName = decoded.getClaim(CLAIM_PROVIDER).asString(),
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

        /** Ktor authentication provider name used for Bearer JWT validation on API routes. */
        const val AUTH_NAME_JWT = "jwt-api"

        /** Prefix for per-provider OIDC OAuth2 authentication provider names. */
        const val AUTH_NAME_OIDC_PREFIX = "oidc-"
    }
}

/**
 * Build the [Algorithm] instance from [config].
 *
 * Only HMAC variants are implemented at present. Asymmetric types (RS*, ES*) are
 * defined in [JwtAlgorithmType] as extension points and will be implemented when a
 * multiservice deployment scenario requires them.
 *
 * @throws IllegalArgumentException if [config] contains no secret for an HMAC algorithm.
 * @throws UnsupportedOperationException if an asymmetric algorithm is selected.
 */
private fun buildAlgorithm(config: SessionConfig): Algorithm {
    require(config.algorithm.isSymmetric) {
        "Algorithm ${config.algorithm} is an asymmetric key-pair type that is not yet " +
            "implemented. Use HS256, HS384, or HS512 with a shared secret for now."
    }
    val secret = requireNotNull(config.secret) {
        "SessionConfig.secret must be set before constructing JwtSessionService. " +
            "Ensure the secret is resolved in serverModule before calling JwtSessionService()."
    }
    return when (config.algorithm) {
        JwtAlgorithmType.HS256 -> Algorithm.HMAC256(secret)
        JwtAlgorithmType.HS384 -> Algorithm.HMAC384(secret)
        JwtAlgorithmType.HS512 -> Algorithm.HMAC512(secret)
        else -> error("Unreachable — isSymmetric guard above covers all non-HMAC variants")
    }
}
