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
) : SsoProviderConfig

/**
 * Header-injection provider for Shibboleth / SAML 2.0 reverse-proxy deployments.
 *
 * In this mode a trusted upstream reverse proxy (Apache httpd with `mod_shib`, or an
 * equivalent Shibboleth SP) authenticates the user via SAML 2.0 and forwards identity
 * attributes as HTTP request headers. The Ktor server extracts the principal from those
 * headers without performing any OAuth/OIDC handshake itself.
 *
 * **Security**: Only trust this provider when the Ktor server is not directly reachable
 * from untrusted networks — the header values are not cryptographically verified by the
 * application itself. Restrict network access at the OS/firewall level.
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
 */
data class HeaderInjectionProviderConfig(
    override val name: String,
    val userHeader: String = "X-Remote-User",
    val emailHeader: String = "X-Shib-Mail",
    val displayNameHeader: String = "X-Shib-Cn",
    val displayName: String = name,
) : SsoProviderConfig
