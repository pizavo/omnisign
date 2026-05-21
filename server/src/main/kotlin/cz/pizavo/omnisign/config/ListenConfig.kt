package cz.pizavo.omnisign.config

/**
 * Network-listener configuration: the bind address and plain-HTTP port the server
 * accepts connections on.
 *
 * Lives under `listen:` (rather than `host:` / `port:` at the root) to match the
 * nesting pattern of [TlsConfig], [ProxyConfig], [CorsConfig], and [AuthConfig] — every
 * other config concept is grouped under a named block, and grouping the network bind
 * makes the schema consistent. The natural-future fields (`backlog`, `tcpKeepAlive`,
 * `socketTimeout`) would land in this block alongside the existing two without
 * touching the root shape.
 *
 * The transport-security validator ([validateTransportSecurity]) reads [host] to
 * decide whether a non-loopback bind requires TLS or a reverse proxy. The
 * development-mode-vs-loopback check in `Application.main` also reads [host].
 *
 * @property host Network interface the server binds to. Loopback values
 *   (`"127.0.0.1"`, `"::1"`, `"localhost"`) skip [validateTransportSecurity]; any
 *   other value requires `tls:` or `proxy.enabled: true`.
 * @property port Port for the plain HTTP connector. Used when TLS is not configured
 *   AND reverse-proxy mode is inactive; otherwise see [TlsConfig.port] for the TLS
 *   connector port.
 */
data class ListenConfig(
	val host: String = "0.0.0.0",
	val port: Int = 50080,
)
