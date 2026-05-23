package cz.pizavo.omnisign.config

/**
 * Validate that the configured network bind is not network-reachable in plain HTTP at
 * server startup.
 *
 * Rule, applied per the [analysis-doc validation table][validateTransportSecurity]:
 *
 * | Bind host                          | `proxy.enabled` | `tls`     | Result |
 * | ---------------------------------- | --------------- | --------- | ------ |
 * | `127.0.0.1` / `::1` / `localhost`  | any             | any       | Silent |
 * | Non-loopback                       | `true`          | any       | Silent |
 * | Non-loopback                       | any             | non-null  | Silent |
 * | Non-loopback                       | `false`/absent  | `null`    | FAIL   |
 *
 * **Why.** Plain HTTP on a network-reachable interface exposes every on-the-wire
 * artifact — JWT access tokens in `Authorization: Bearer`, OIDC `code`/`state` in the
 * callback redirect, refresh tokens, signed PDFs, and any other secret-bearing
 * response — to any on-path observer. For a signing service that combination is
 * non-starter in production. The previous behavior accepted it with only an INFO log
 * line.
 *
 * Tying the requirement to actual reachability (loopback vs not) rather than to the
 * `development` flag is deliberate: a dev-mode flag can leak into production, but a
 * bind to `0.0.0.0` is always observably network-reachable. Operators who genuinely
 * need plain HTTP for local development bind to `127.0.0.1`; everyone else gets a
 * loud startup failure pointing at the two valid options (direct TLS or reverse
 * proxy).
 *
 * No `development: true` escape hatch — adding one would re-create the H-1 footgun
 * where a dev flag leaks into production deployment. Operators who need plain HTTP
 * during development should bind to loopback.
 *
 * @param serverConfig The root server configuration.
 * @throws IllegalArgumentException with operator-actionable guidance when the bind host
 *   is network-reachable but neither [ServerConfig.proxy] nor [ServerConfig.tls] is
 *   configured.
 */
fun validateTransportSecurity(serverConfig: ServerConfig) {
	if (isLoopbackHost(serverConfig.listen.host)) return
	val proxyEnabled = serverConfig.proxy?.enabled == true
	if (proxyEnabled) return
	if (serverConfig.tls != null) return

	throw IllegalArgumentException(
		"host '${serverConfig.listen.host}' is network-reachable but neither proxy nor TLS is " +
			"configured. Plain HTTP would expose JWT tokens, OIDC callback parameters, " +
			"and refresh tokens to any on-path observer. Set proxy.enabled: true (TLS " +
			"terminated by an upstream reverse proxy) or tls: { keystorePath, … } " +
			"(TLS terminated directly by Ktor). For local development without TLS " +
			"setup, bind to 127.0.0.1 or ::1.",
	)
}

/**
 * Strict literal match for the three accepted loopback bind addresses.
 *
 * No DNS resolution and no IP-range matching: `127.0.0.2` is rejected (it IS in
 * `127.0.0.0/8` but operators virtually never bind to it), and an arbitrary hostname
 * is rejected too (DNS dependency at startup is a footgun). Same rationale as
 * [parseTrustedProxy] only accepting IP/CIDR literals for `proxy.trusted`.
 *
 * `localhost` is treated as a loopback alias because that is the universal convention
 * even though it is technically a hostname; the resolver almost always maps it to
 * `127.0.0.1` or `::1` and an operator who writes `host: "localhost"` does mean
 * loopback. The two callers ([validateTransportSecurity] and the development-mode
 * check in `Application.kt`) need the same set, so the predicate lives here as a
 * single source of truth.
 *
 * @param host The value of [ListenConfig.host].
 * @return `true` when [host] is `127.0.0.1`, `::1`, or `localhost`.
 */
fun isLoopbackHost(host: String): Boolean =
	host in setOf("127.0.0.1", "::1", "localhost")
