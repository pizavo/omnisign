package cz.pizavo.omnisign.config

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
 */
data class AuthConfig(
	val enabled: Boolean = false,
	val providers: List<SsoProviderConfig> = emptyList(),
	val session: SessionConfig = SessionConfig(),
)

