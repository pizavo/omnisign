package cz.pizavo.omnisign.config

/**
 * Reverse-proxy configuration.
 *
 * Replaces the original flat `proxyMode: Boolean` field with an explicit block that pairs
 * the on/off switch with the trust boundary it depends on. The pairing is enforced at
 * server startup so `proxy.enabled: true` without a populated [trusted] list cannot start
 * a server — historically that combination accepted any TCP peer's `X-Forwarded-*`
 * headers verbatim, which is a rate-limit-bypass and audit-log-poisoning vector.
 *
 * ### Validation rules (applied by [validateProxyConfig] at startup)
 *
 * | YAML form                                                  | Result   |
 * | ---------------------------------------------------------- | -------- |
 * | `proxy:` absent, or `proxy: { enabled: false }`            | Silent — direct-connection mode |
 * | `proxy: { enabled: false, trusted: [...] }`                | WARN — list is ignored |
 * | `proxy: { enabled: true }` or `trusted: []`                | FAIL — `trusted` must list proxy IPs |
 * | `proxy: { enabled: true, trusted: ["*"] }`                 | FAIL — `"*"` defeats the trust boundary |
 * | `proxy: { enabled: true, trusted: ["bogus.host"] }`        | FAIL — entry is not a valid IP / CIDR |
 * | `proxy: { enabled: true, trusted: ["127.0.0.1", "::1"] }`  | Silent — same-host loopback |
 * | `proxy: { enabled: true, trusted: ["10.0.0.0/24"] }`       | Silent — CIDR range |
 *
 * ### Why `"*"` is rejected (asymmetry with CORS `allowedOrigins: ["*"]`)
 *
 * - **CORS** `["*"]` is a legitimate operator intent for a genuinely public API. The grant
 *   is "any web origin may call us" — there is a real deployment shape where that is the
 *   correct answer.
 * - **`proxy.trusted` `["*"]`** has no legitimate operator intent. The grant would read as
 *   "I trust any IP on Earth to override request metadata," which is the M-7 vulnerability
 *   with a friendlier face. The trust boundary here is not about *who can call the API*
 *   (any client always can — that is what TCP is for); it is about *whose claims about
 *   request origin do I believe*. No defensible deployment answers "anyone."
 *
 * ### Accepted entry forms
 *
 * IP literal or CIDR range only — no hostnames, no wildcards. Same convention as nginx
 * `real_ip_from`, Caddy `trusted_proxies`, and Apache `RemoteIPInternalProxy`. Hostnames
 * are rejected because (a) DNS resolution at startup introduces a runtime dependency on
 * the resolver, with DNS-poisoning as a minor vector; (b) `localhost` resolves
 * inconsistently across platforms (`127.0.0.1` on some, `::1` on others) and operators
 * routinely conflate the two. Operators who want loopback must write both `"127.0.0.1"`
 * and `"::1"` explicitly.
 *
 * @property enabled When `true`, `X-Forwarded-*` headers from connections originating at
 *   IPs in [trusted] are honored so the server sees the real client IP for rate limiting
 *   and audit logging. TLS termination is then assumed to be handled by the reverse proxy
 *   and the HTTPS-redirect plugin is skipped.
 * @property trusted IP addresses or CIDR ranges of trusted reverse proxies. Required and
 *   non-empty whenever [enabled] is `true`. See the validation table above for the exact
 *   rules.
 */
data class ProxyConfig(
	val enabled: Boolean = false,
	val trusted: List<String> = emptyList(),
)
