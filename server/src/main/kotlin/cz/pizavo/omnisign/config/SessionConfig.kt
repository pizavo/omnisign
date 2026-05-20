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
 * @property algorithm JWT signing algorithm. Defaults to [JwtAlgorithmType.HS512].
 * @property secret HMAC signing secret. Ignored for asymmetric algorithms. Typically
 *   declared via env-var substitution in the YAML rather than written inline.
 * @property issuer JWT `iss` claim value.
 * @property audience JWT `aud` claim value.
 * @property tokenExpirySeconds Access-token lifetime in seconds. Defaults to one hour.
 *   Operators that want short-lived access tokens with refresh-based renewal can drop
 *   this to e.g. 300 (5 min); legitimate clients keep refreshing within
 *   [maxSessionSeconds], stolen tokens are bounded to the short window.
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
    val tokenExpirySeconds: Long = 3600,
    val maxSessionSeconds: Long = 28_800,
)
