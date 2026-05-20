package cz.pizavo.omnisign.config

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
 * @property clientSecret OAuth2 / OIDC `client_secret`.
 * @property discoveryUrl Full OIDC discovery document URL. Overrides the preset default.
 * @property tenantId Tenant, realm, or domain string for presets with templated discovery URLs.
 * @property scopes OAuth2 scope list. Overrides the preset default.
 * @property displayName Human-readable provider label shown in the login UI.
 * @property allowedEmailDomains When non-null and non-empty, only users whose resolved email
 *   belongs to one of the listed domains are granted a session token. The check runs after the
 *   IdP callback, once the email claim is available. Domains are compared case-insensitively
 *   (e.g. `["contoso.com", "fabrikam.com"]`). When `null`, all authenticated users are accepted
 *   regardless of their email domain.
 * @property requiredClaims When non-null and non-empty, the user's raw IdP claims must satisfy
 *   every entry: for each `(claimName, values)` pair, the claim must contain at least one of
 *   the listed values. Both single-valued string claims (e.g. `schac_home_organization`) and
 *   multivalued array claims (e.g. `eduperson_scoped_affiliation`) are supported. Useful for
 *   restricting access by institution or affiliation role without relying on email domain alone.
 * @property pkce Whether to perform PKCE (RFC 7636) on this provider's authorization-code flow.
 *   Defaults to `true` — every modern IdP supports PKCE and the OAuth 2.1 BCP (RFC 9700)
 *   requires it even for confidential clients to defend against authorization-code injection.
 *   Set to `false` only for legacy or homegrown IdPs that reject the `code_challenge` /
 *   `code_verifier` parameters; doing so weakens the flow's security and should be a last
 *   resort.
 */
data class OidcProviderConfig(
    override val name: String,
    val preset: SsoProviderPreset? = null,
    val clientId: String,
    val clientSecret: String,
    val discoveryUrl: String? = null,
    val tenantId: String? = null,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val displayName: String = name,
    val allowedEmailDomains: List<String>? = null,
    val requiredClaims: Map<String, List<String>>? = null,
    val pkce: Boolean = true,
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
 *   request it forwards.
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
    val sharedSecret: String,
    val sharedSecretHeader: String = "X-Header-Injection-Token",
) : SsoProviderConfig {
    init {
        require(sharedSecret.toByteArray(Charsets.UTF_8).size >= MIN_SHARED_SECRET_BYTES) {
            "HeaderInjectionProviderConfig '$name': sharedSecret must be at least " +
                "$MIN_SHARED_SECRET_BYTES bytes (512 bits) — got " +
                "${sharedSecret.toByteArray(Charsets.UTF_8).size}. Generate one with " +
                "`openssl rand -base64 64`."
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
