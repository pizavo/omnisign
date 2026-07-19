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
 * The OIDC `client_secret` is deliberately NOT a field on this class — see [ServerSecrets].
 * It is resolved at startup from a per-provider environment variable named via
 * [oidcClientSecretEnvVar] (e.g. `name: "google"` → `OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET`).
 * A YAML attempt to set `clientSecret:` fails startup with an error pointing at the
 * derived env var.
 *
 * @property name Unique provider identifier used in callback URLs and UI (e.g. `microsoft`).
 * @property preset Optional well-known preset that fills in default URLs and scopes.
 * @property clientId OAuth2 / OIDC `client_id`.
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
    val discoveryUrl: String? = null,
    val tenantId: String? = null,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val displayName: String = name,
    val allowedEmailDomains: List<String>,
    val requiredClaims: Map<String, List<String>>? = null,
    val pkce: Boolean = true,
    val verifyIdToken: Boolean = true,
) : SsoProviderConfig
