package cz.pizavo.omnisign.config

/**
 * Configuration for the JWT session tokens issued to clients after a successful SSO login.
 *
 * [algorithm] defaults to [JwtAlgorithmType.HS512]. For most single-server OmniSign
 * deployments this is the correct choice — HS512 is fast, simple to configure, and
 * provides the same operational security as asymmetric alternatives when only one process
 * issues and validates tokens. See [JwtAlgorithmType] for a full comparison and for
 * guidance on when asymmetric algorithms (RS* / ES*) are appropriate.
 *
 * The secret is resolved in order: [secret] field → `OMNISIGN_JWT_SECRET` environment
 * variable → auto-generated ephemeral value in development mode (triggers a startup
 * warning; tokens are invalidated on restart). In production the server refuses to start
 * without an explicit secret.
 *
 * @property algorithm JWT signing algorithm. Defaults to [JwtAlgorithmType.HS512].
 * @property secret HMAC signing secret. Ignored for asymmetric algorithms.
 *   Prefer the `OMNISIGN_JWT_SECRET` environment variable over committing this value.
 * @property issuer JWT `iss` claim value.
 * @property audience JWT `aud` claim value.
 * @property tokenExpirySeconds Access-token lifetime in seconds. Defaults to one hour.
 */
data class SessionConfig(
    val algorithm: JwtAlgorithmType = JwtAlgorithmType.HS512,
    val secret: String? = null,
    val issuer: String = "omnisign",
    val audience: String = "omnisign-api",
    val tokenExpirySeconds: Long = 3600,
)
