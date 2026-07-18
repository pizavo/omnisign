package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * Root authentication configuration block for the OmniSign server.
 *
 * When `null` (not present in `server.yml`) the server starts without authentication
 * support. When present and [enabled] is `true`, all API routes except `/api/v1/health`
 * and the `/auth/​**` endpoints require a valid JWT session token.
 *
 * Example `server.yml` snippet. Note: secret-bearing fields (`session.secret`, OIDC
 * `clientSecret`) are resolved from environment variables — they cannot appear in YAML.
 * See [ServerSecrets] for the env-var names.
 *
 * ```yaml
 * auth:
 *   enabled: true
 *   session:
 *     issuer: omnisign
 *     tokenExpirySeconds: 3600
 *     # secret resolved from OMNISIGN_JWT_SECRET
 *   providers:
 *     - type: oidc
 *       name: google
 *       preset: GOOGLE
 *       clientId: "…"
 *       allowedEmailDomains: ["yourcompany.com"]
 *       # clientSecret resolved from OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET
 *     - type: oidc
 *       name: microsoft
 *       preset: MICROSOFT
 *       tenantId: "common"
 *       clientId: "…"
 *       allowedEmailDomains: ["contoso.com"]
 *       # clientSecret resolved from OMNISIGN_OIDC_MICROSOFT_CLIENT_SECRET
 * ```
 *
 * @property enabled When `true`, all operational API routes require a valid JWT Bearer token.
 *   Must be combined with a non-empty [providers] list to be functional.
 * @property providers Ordered list of active SSO providers.
 * @property session JWT session token settings.
 * @property allowedRedirectUris URLs a completed login may hand the browser back to, listed in
 *   full and matched exactly. Empty by default, which disables the hand-off entirely and leaves
 *   the callback answering with token JSON — right for a server with no browser front-end.
 *
 *   Populate it with the page the OmniSign web app is served from
 *   (e.g. `["https://omnisign.example.com/"]`) to let the app sign users in. The app asks to be
 *   returned to one of these; anything not on the list is refused at the callback.
 *
 *   Matching is exact — not a prefix, not a host comparison — and that is the whole point of the
 *   field. A prefix match on `https://omnisign.example.com` would also accept
 *   `https://omnisign.example.com.attacker.test`, and a host match would accept any path on the
 *   host including one an attacker can influence. Since the callback delivers a hand-off code to
 *   whatever URL this permits, a loose match here turns the login endpoint into a credential
 *   delivery service for someone else's page. Entries must be absolute URLs; `http` is accepted
 *   only for loopback hosts, so a development origin works and a production one cannot be
 *   downgraded by a typo. Rejected at startup otherwise ([validateAuthConfig]).
 * @property oauthStateSecret HMAC key used to sign and verify the OAuth2 `state` parameter
 *   on the authorization-code callback (CSRF protection for the login flow). Must be at
 *   least 32 bytes when supplied. Typically declared via env-var substitution:
 *   `oauthStateSecret: "${OMNISIGN_OAUTH_NONCE_SECRET}"`. When `null` and OIDC providers
 *   are configured, the server falls back to an ephemeral random key in development mode
 *   (with a warning) and fails to start in production. Wrapped in [Sensitive] so the
 *   value cannot leak through `toString` (data-class-generated, logger interpolation,
 *   status pages echoing `cause.message`).
 */
data class AuthConfig(
	val enabled: Boolean = false,
	val providers: List<SsoProviderConfig> = emptyList(),
	val session: SessionConfig = SessionConfig(),
	val allowedRedirectUris: List<String> = emptyList(),
	val oauthStateSecret: Sensitive<String>? = null,
)

