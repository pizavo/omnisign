package cz.pizavo.omnisign.config

/**
 * Configuration for the HTTP Strict Transport Security (HSTS) response header.
 *
 * When present in [TlsConfig.hsts], the `Strict-Transport-Security` header is appended
 * to every response, instructing browsers to always use HTTPS for the configured duration.
 *
 * **Important**: Only enable HSTS when the server (or the reverse proxy in front of it) is
 * reachable exclusively over HTTPS. Sending this header over plain HTTP is a protocol error
 * and can lock clients out of the site. When running behind a TLS-terminating proxy, set
 * the proxy to inject this header rather than enabling it here.
 *
 * HSTS `preload` registration requires `includeSubDomains: true`, `maxAgeSeconds` ≥ 31536000,
 * and explicit submission to [hstspreload.org](https://hstspreload.org/). The [preload]
 * directive signals intent to be included in the browser preload list.
 *
 * @property maxAgeSeconds Duration in seconds that browsers must remember the HTTPS preference.
 *   Defaults to 31 536 000 (one year). Use a shorter value (e.g., 300) during initial rollout
 *   to allow reverting without locking users out.
 * @property includeSubDomains When `true`, the policy is also applied to all subdomains.
 *   Defaults to `true`.
 * @property preload When `true`, the `preload` directive is appended, signaling intent to
 *   submit the domain to the HSTS preload list. Defaults to `false`. Requires
 *   [includeSubDomains] to be `true` and [maxAgeSeconds] ≥ 31 536 000.
 */
data class HstsConfig(
	val maxAgeSeconds: Long = 31_536_000L,
	val includeSubDomains: Boolean = true,
	val preload: Boolean = false,
)

