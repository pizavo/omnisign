package cz.pizavo.omnisign.config

/**
 * Root server configuration loaded from the `server.yml` file.
 *
 * Every config concept lives under a named block — `listen:` for the network bind,
 * `operations:` for the operation allowlist, plus `tls:` / `proxy:` / `cors:` / `auth:`
 * / `rateLimiting:` — so the schema is consistent and future field additions land in
 * the right block without reshaping the root.
 *
 * @property listen Network listener: bind host and plain-HTTP port. See [ListenConfig].
 * @property development When `true`, Ktor development mode is activated. This enables
 *   auto-reload and more verbose error pages. Should be `false` in production.
 * @property proxy Reverse-proxy configuration. When `null` or [ProxyConfig.enabled] is
 *   `false`, the server runs in direct-connection mode and ignores `X-Forwarded-*`
 *   headers. When enabled, [ProxyConfig.trusted] must list the IP addresses or CIDR
 *   ranges of the upstream proxies whose forwarded headers are honored; see
 *   [ProxyConfig] for the full validation contract.
 * @property operations Operation-gating: allowed API operations and certificate-alias
 *   allowlist for signing. See [OperationsConfig].
 * @property tls TLS/SSL keystore settings, including optional nested [HstsConfig].
 *   Ignored when reverse-proxy mode is active.
 * @property cors Cross-Origin Resource Sharing configuration.
 * @property rateLimiting Per-IP request rate limiting for auth and API endpoints.
 *   When `null`, rate limiting is disabled.
 * @property maxFileSize Maximum upload file size in bytes. Defaults to 100 MB.
 * @property auth SSO authentication configuration. When `null`, no authentication plugin
 *   is installed. Set [AuthConfig.enabled] to `true` within this block to enforce JWT
 *   authentication on all operational routes.
 */
data class ServerConfig(
	val listen: ListenConfig = ListenConfig(),
	val development: Boolean = false,
	val proxy: ProxyConfig? = null,
	val operations: OperationsConfig = OperationsConfig(),
	val tls: TlsConfig? = null,
	val cors: CorsConfig? = null,
	val rateLimiting: RateLimitConfig? = null,
	val maxFileSize: Long = 100L * 1024 * 1024,
	val auth: AuthConfig? = null,
)
