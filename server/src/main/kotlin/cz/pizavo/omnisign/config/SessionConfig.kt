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
 * The [secret] is read from the YAML field directly. Operators typically declare it via
 * env-var substitution so the actual key value never lands in the YAML file:
 * `secret: "${OMNISIGN_JWT_SECRET}"` is expanded at config-load time. When auth is
 * enabled the secret must be present and at least 64 bytes (enforced in serverModule);
 * when auth is disabled it may be omitted entirely.
 *
 * ## Access tokens and refresh tokens
 *
 * The server issues two distinct tokens on every login and every refresh:
 * - A short-lived **JWT access token** ([tokenExpirySeconds], default 5 minutes) sent on
 *   every API call as `Authorization: Bearer <token>`. Self-contained, no DB lookup.
 * - A long-lived **opaque refresh token** ([refreshTokenLifetimeSeconds], default 30
 *   days) stored server-side in the [cz.pizavo.omnisign.auth.RefreshTokenStore]. Used
 *   only against `/auth/refresh` and `/auth/logout`. Rotated on every refresh.
 *
 * A stolen access token is usable only until natural expiry (≤ 5 minutes). A stolen
 * refresh token is invalidated the next time the legitimate user refreshes (rotation).
 * Logout deletes the refresh token from the store. Overall session lifetime is bounded
 * by [maxSessionSeconds] independent of refresh-token TTL.
 *
 * @property algorithm JWT signing algorithm. Defaults to [JwtAlgorithmType.HS512].
 * @property secret HMAC signing secret. Ignored for asymmetric algorithms. Typically
 *   declared via env-var substitution in the YAML rather than written inline.
 * @property issuer JWT `iss` claim value.
 * @property audience JWT `aud` claim value.
 * @property tokenExpirySeconds Access-token lifetime in seconds. Defaults to 300 (5 min).
 *   Short by design: combined with the refresh-token rotation in
 *   [cz.pizavo.omnisign.auth.RefreshTokenStore], this bounds the blast radius of a
 *   stolen access token to a few minutes. Operators with non-refreshing clients should
 *   raise this to something the client can live with (e.g., 3600).
 * @property refreshTokenLifetimeSeconds Refresh-token lifetime in seconds. Defaults to
 *   2 592 000 (30 days). Every successful `/auth/refresh` issues a fresh refresh token
 *   with a TTL of this length, so the absolute session lifetime is effectively
 *   `min(refreshTokenLifetimeSeconds × refresh-count, maxSessionSeconds)`.
 * @property maxSessionSeconds Absolute upper bound, in seconds, on the time between the
 *   original SSO authentication and any `/auth/refresh` that successfully mints a new
 *   token. Defaults to 28 800 (8 hours) — a typical workday. The check compares the
 *   token's `auth_time` claim (set on the initial login and preserved across refreshes)
 *   against the current time; refresh requests beyond this window are rejected with
 *   `401 SESSION_EXPIRED` and the user must re-authenticate via the IdP. Bounds how
 *   long a stolen access-token-plus-refresh-chain can keep working.
 */
data class SessionConfig(
    val algorithm: JwtAlgorithmType = JwtAlgorithmType.HS512,
    val secret: String? = null,
    val issuer: String = "omnisign",
    val audience: String = "omnisign-api",
    val tokenExpirySeconds: Long = 300,
    val refreshTokenLifetimeSeconds: Long = 30L * 24 * 3600,
    val maxSessionSeconds: Long = 28_800,
)
