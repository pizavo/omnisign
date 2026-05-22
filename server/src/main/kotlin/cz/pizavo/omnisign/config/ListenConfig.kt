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
 * @property host Network interface the server binds to. Defaults to `"127.0.0.1"`
 *   (loopback) so the out-of-box deployment is reachable only from the same machine
 *   and a fresh `gradlew :server:run` works without TLS or proxy setup. Loopback
 *   values (`"127.0.0.1"`, `"::1"`, `"localhost"`) skip [validateTransportSecurity];
 *   any other value (e.g. `"0.0.0.0"`, a public IP, a private IP) is network-reachable
 *   and requires `tls:` or `proxy.enabled: true`, otherwise startup fails so plain
 *   HTTP cannot accidentally expose JWT tokens or OIDC callback parameters on the
 *   wire. Operators who want LAN/public reach must explicitly opt in by setting this
 *   field — the safe default is preserved unless the operator deliberately overrides
 *   it AND simultaneously satisfies the transport-security rule.
 * @property port Port for the plain HTTP connector. Used when TLS is not configured
 *   AND reverse-proxy mode is inactive; otherwise see [TlsConfig.port] for the TLS
 *   connector port.
 */
data class ListenConfig(
	val host: String = "127.0.0.1",
	val port: Int = 18080,
)
