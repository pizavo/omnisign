package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * Root authentication configuration block for the OmniSign server.
 *
 * When `null` (not present in `server.yml`) the server starts without authentication
 * support. When present and [enabled] is `true`, all API routes except `/api/v1/health`
 * and the `/auth/​**` endpoints require a valid JWT session token.
 *
 * Example `server.yml` snippet:
 * ```yaml
 * auth:
 *   enabled: true
 *   session:
 *     issuer: omnisign
 *     tokenExpirySeconds: 3600
 *   providers:
 *     - type: oidc
 *       name: google
 *       preset: GOOGLE
 *       clientId: "…"
 *       clientSecret: "…"
 *     - type: oidc
 *       name: microsoft
 *       preset: MICROSOFT
 *       tenantId: "common"
 *       clientId: "…"
 *       clientSecret: "…"
 *     - type: header-injection
 *       name: shibboleth
 *       userHeader: "X-Remote-User"
 *       emailHeader: "X-Shib-Mail"
 *       displayNameHeader: "X-Shib-Cn"
 * ```
 *
 * @property enabled When `true`, all operational API routes require a valid JWT Bearer token.
 *   Must be combined with a non-empty [providers] list to be functional.
 * @property providers Ordered list of active SSO providers.
 * @property session JWT session token settings.
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
	val oauthStateSecret: Sensitive<String>? = null,
)

