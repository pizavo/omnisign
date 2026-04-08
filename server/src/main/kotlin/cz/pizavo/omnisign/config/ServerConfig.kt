package cz.pizavo.omnisign.config

/**
 * Root server configuration loaded from the `server.yml` file.
 *
 * @property host Network interface the server binds to.
 * @property port Port for the plain HTTP connector (used when [proxyMode] is `true` or TLS is not configured).
 * @property tlsPort Port for the TLS connector when TLS is configured and [proxyMode] is `false`.
 * @property development When `true`, Ktor development mode is activated. This enables
 *   auto-reload and more verbose error pages. Should be `false` in production.
 * @property proxyMode When `true`, TLS termination is handled by a reverse proxy and the server
 *   listens on a plain HTTP connector. `X-Forwarded-*` headers are trusted.
 * @property requireLogin When `true`, the API requires authentication. The actual authentication
 *   mechanism is not yet implemented — this flag serves as a future extension point.
 * @property allowedOperations Set of operations the server exposes. Defaults to [AllowedOperation.VALIDATE]
 *   and [AllowedOperation.TIMESTAMP]. [AllowedOperation.SIGN] is opt-in for institutional deployments
 *   where the server holds an HSM or seal certificate.
 * @property allowedCertificateAliases When non-null, only these certificate aliases may be used
 *   for signing via the API. Provides defense-in-depth so that personal certificates installed
 *   on the server are never accidentally exposed. When `null` and signing is enabled, all
 *   discovered signing certificates are available.
 * @property tls TLS/SSL keystore settings. Ignored when [proxyMode] is `true`.
 * @property cors Cross-Origin Resource Sharing configuration.
 * @property compression Response compression configuration.
 * @property maxFileSize Maximum upload file size in bytes. Defaults to 100 MB.
 */
data class ServerConfig(
	val host: String = "0.0.0.0",
	val port: Int = 50080,
	val tlsPort: Int = 50443,
	val development: Boolean = false,
	val proxyMode: Boolean = false,
	val requireLogin: Boolean = false,
	val allowedOperations: Set<AllowedOperation> = setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP),
	val allowedCertificateAliases: List<String>? = null,
	val tls: TlsConfig? = null,
	val cors: CorsConfig? = null,
	val compression: CompressionConfig = CompressionConfig(),
	val maxFileSize: Long = 100L * 1024 * 1024,
)

