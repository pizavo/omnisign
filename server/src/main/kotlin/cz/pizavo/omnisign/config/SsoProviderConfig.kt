package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * Sealed hierarchy of supported SSO provider configurations.
 *
 * Each subtype represents one authentication mechanism. Multiple providers may be listed
 * under [AuthConfig.providers] — the client picks one at login time via the provider name.
 */
sealed interface SsoProviderConfig {
    /** Unique identifier used in route paths and log messages. */
    val name: String
}

/**
 * OIDC / OAuth2 authorization-code-flow provider.
 *
 * If [preset] is set, sensible defaults for [discoveryUrl] and [scopes] are applied
 * automatically; any explicitly provided value still takes precedence.
 *
 * For providers that require tenant / realm scoping ([SsoProviderPreset.MICROSOFT],
 * [SsoProviderPreset.AMAZON_COGNITO], [SsoProviderPreset.KEYCLOAK], [SsoProviderPreset.AUTH0])
 * supply [tenantId] so the discovery URL template can be resolved. See [SsoProviderPreset]
 * KDoc for the expected format of each preset.
 *
 * @property name Unique provider identifier used in callback URLs and UI (e.g. `microsoft`).
 * @property preset Optional well-known preset that fills in default URLs and scopes.
 * @property clientId OAuth2 / OIDC `client_id`.
 * @property clientSecret OAuth2 / OIDC `client_secret`. Wrapped in [Sensitive] so it
 *   cannot leak through `toString` (data-class-generated, logger interpolation, status
 *   pages echoing `cause.message`).
 * @property discoveryUrl Full OIDC discovery document URL. Overrides the preset default.
 * @property tenantId Tenant, realm, or domain string for presets with templated discovery URLs.
 * @property scopes OAuth2 scope list. Overrides the preset default.
 * @property displayName Human-readable provider label shown in the login UI.
 * @property allowedEmailDomains Required, non-empty list controlling which authenticated users
 *   are granted a session token. Operators must always make a deliberate, explicit choice — there
 *   is no implicit allow-all default, because for a signing service "didn't think about it",
 *   "explicitly allow all", and "explicitly deny all" must not collapse into the same runtime
 *   outcome. Supported forms:
 *   - `["*"]` — explicit allow-all (parallel to CORS `allowedOrigins: ["*"]`); accept any
 *     authenticated user regardless of email domain. Use this when the IdP itself is the
 *     identity boundary (e.g., a private Entra ID tenant the operator controls).
 *   - `["contoso.com", "fabrikam.com"]` — accept only users whose resolved email domain
 *     case-insensitively matches one of the listed entries. The check runs after the IdP
 *     callback, once the email claim is available.
 *
 *   Empty list and missing field are both rejected at server startup with a message naming
 *   the offending provider. See [EmailDomainFilter][isEmailDomainAllowed] for the runtime check.
 * @property requiredClaims When non-null and non-empty, the user's raw IdP claims must satisfy
 *   every entry: for each `(claimName, values)` pair, the claim must contain at least one of
 *   the listed values. Both single-valued string claims (e.g. `schac_home_organization`) and
 *   multivalued array claims (e.g. `eduperson_scoped_affiliation`) are supported. Useful for
 *   restricting access by institution or affiliation role without relying on email domain alone.
 *
 *   A `key: []` value list (empty accepted-values list for a key) is rejected at server startup
 *   — it would reject every login on that claim and is almost certainly a typo.
 * @property pkce Whether to perform PKCE (RFC 7636) on this provider's authorization-code flow.
 *   Defaults to `true` — every modern IdP supports PKCE and the OAuth 2.1 BCP (RFC 9700)
 *   requires it even for confidential clients to defend against authorization-code injection.
 *   Set to `false` only for legacy or homegrown IdPs that reject the `code_challenge` /
 *   `code_verifier` parameters; doing so weakens the flow's security and should be a last
 *   resort.
 * @property verifyIdToken Whether to parse and cryptographically verify the OIDC `id_token`
 *   returned by the IdP on every callback. Defaults to `true` — OIDC mandates that any
 *   provider serving the `openid` scope returns a signed id_token, and verifying its
 *   signature against the IdP's `jwks_uri` plus its `iss` / `aud` / `exp` claims gives
 *   defense in depth against UserInfo tampering and against trusting any IdP-shaped
 *   endpoint that simply returns a plausible identity. Set to `false` for OAuth2-only
 *   providers that do not issue id_tokens (e.g., GitHub via [SsoProviderPreset.GITHUB]);
 *   the callback then falls back to UserInfo alone.
 */
data class OidcProviderConfig(
    override val name: String,
    val preset: SsoProviderPreset? = null,
    val clientId: String,
    val clientSecret: Sensitive<String>,
    val discoveryUrl: String? = null,
    val tenantId: String? = null,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val displayName: String = name,
    val allowedEmailDomains: List<String>,
    val requiredClaims: Map<String, List<String>>? = null,
    val pkce: Boolean = true,
    val verifyIdToken: Boolean = true,
) : SsoProviderConfig

/**
 * Header-injection provider for Shibboleth / SAML 2.0 reverse-proxy deployments.
 *
 * In this mode a trusted upstream reverse proxy (Apache httpd with `mod_shib`, or an
 * equivalent Shibboleth SP) authenticates the user via SAML 2.0 and forwards identity
 * attributes as HTTP request headers. The Ktor server extracts the principal from those
 * headers without performing any OAuth/OIDC handshake itself.
 *
 * **Transport-level integrity is enforced application-side.** Header values cannot be
 * cryptographically verified by their content alone — an attacker who reaches the Ktor
 * port directly could otherwise forge any identity by setting [userHeader]. To close
 * that gap the callback requires a shared secret in [sharedSecretHeader]; the reverse
 * proxy must inject both the identity headers and the secret, and the Ktor route
 * rejects any request whose secret value does not match [sharedSecret] (constant-time
 * comparison). Operators should still restrict network access at the OS/firewall level
 * — defence in depth — but the secret-check makes header forgery non-trivial even if
 * the network boundary is misconfigured.
 *
 * Common Shibboleth attribute header names (may vary by IdP / SP configuration):
 * - User principal: `REMOTE_USER` or `X-Remote-User` or `X-Shib-Uid`
 * - E-mail: `X-Shib-Mail` or `Mail`
 * - Display name: `X-Shib-Cn` or `Cn`
 *
 * @property name Unique provider identifier (e.g. `shibboleth` or `eduid`).
 * @property userHeader Header name that carries the authenticated user's unique identifier.
 * @property emailHeader Header name for the user's e-mail address.
 * @property displayNameHeader Header name for the user's full display name.
 * @property displayName Human-readable label shown in the login UI.
 * @property sharedSecret Bearer-style shared secret that the trusted reverse proxy must
 *   inject in [sharedSecretHeader] on every header-injection callback. Required, at
 *   least 64 bytes of entropy (typically generated with `openssl rand -base64 64`).
 *   Declare in `server.yml` via env-var substitution to keep it out of the file:
 *   `sharedSecret: "${OMNISIGN_SHIB_TOKEN}"`. The reverse proxy must be configured
 *   to inject the same value as the [sharedSecretHeader] header on each authenticated
 *   request it forwards. Wrapped in [Sensitive] so the value cannot leak through
 *   `toString` (data-class-generated, logger interpolation, status pages echoing
 *   `cause.message`).
 * @property sharedSecretHeader Name of the header that carries [sharedSecret]. Defaults
 *   to `X-Header-Injection-Token`. Pick something unlikely to collide with other
 *   infrastructure headers; the value is matched case-insensitively by Ktor.
 */
data class HeaderInjectionProviderConfig(
    override val name: String,
    val userHeader: String = "X-Remote-User",
    val emailHeader: String = "X-Shib-Mail",
    val displayNameHeader: String = "X-Shib-Cn",
    val displayName: String = name,
    val sharedSecret: Sensitive<String>,
    val sharedSecretHeader: String = "X-Header-Injection-Token",
) : SsoProviderConfig {
    init {
        val secretBytes = sharedSecret.value.toByteArray(Charsets.UTF_8).size
        require(secretBytes >= MIN_SHARED_SECRET_BYTES) {
            "HeaderInjectionProviderConfig '$name': sharedSecret must be at least " +
                "$MIN_SHARED_SECRET_BYTES bytes (512 bits) — got $secretBytes. Generate one " +
                "with `openssl rand -base64 64`."
        }
        require(sharedSecretHeader.isNotBlank()) {
            "HeaderInjectionProviderConfig '$name': sharedSecretHeader must not be blank."
        }
    }

    companion object {
        /**
         * Minimum acceptable length, in bytes, of the shared secret that the trusted reverse
         * proxy injects on each header-injection callback. 64 bytes (512 bits) is well above
         * the brute-force-infeasibility threshold for a bearer-style token (which 32 bytes
         * would also clear) and matches the floor applied to the server's other secrets
         * (`MIN_JWT_SECRET_BYTES`, `MIN_NONCE_KEY_BYTES`) for a single uniform rule across
         * the auth surface.
         */
        const val MIN_SHARED_SECRET_BYTES = 64
    }
}
