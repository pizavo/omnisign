package cz.pizavo.omnisign.config

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result of [validateProxyConfig].
 *
 * @property enabled Whether reverse-proxy mode is active. Mirrors [ProxyConfig.enabled]
 *   but is `false` when [ProxyConfig] itself was `null`.
 * @property trustedProxies The parsed trusted-proxy entries. Empty when [enabled] is
 *   `false`. Pass through to [cz.pizavo.omnisign.plugins.configureForwardedHeaders] so
 *   the request-time check operates on already-parsed entries.
 */
data class ParsedProxyConfig(
	val enabled: Boolean,
	val trustedProxies: List<TrustedProxy>,
)

/**
 * Validate operator-supplied reverse-proxy configuration at server startup and parse the
 * trusted-proxy entries once for reuse on the request path.
 *
 * Rules (see [ProxyConfig] for the rationale of each):
 * - `null` config or `enabled=false` → returns disabled mode, no parsing.
 * - `enabled=false` with a non-empty `trusted` list → warns that the list is ignored.
 * - `enabled=true` with empty `trusted` → fails with operator guidance.
 * - `enabled=true` with `"*"` anywhere in `trusted` → fails with the wildcard-rejection
 *   message explaining the asymmetry with CORS.
 * - `enabled=true` with any unparseable entry → fails naming the index and the offending
 *   value so the operator can find the line.
 *
 * Called from [cz.pizavo.omnisign.moduleWith] before any plugin that depends on the
 * forwarded-header behavior is installed.
 *
 * @param config The raw [ProxyConfig], or `null` when the `proxy:` block is absent.
 * @return A [ParsedProxyConfig] capturing whether proxy mode is active and the parsed
 *   trusted entries.
 * @throws IllegalArgumentException with an operator-actionable message on any rejection.
 */
fun validateProxyConfig(config: ProxyConfig?): ParsedProxyConfig {
	if (config == null || !config.enabled) {
		if (config != null && config.trusted.isNotEmpty()) {
			logger.warn {
				"proxy.trusted is set but proxy.enabled is false — the list is ignored. " +
					"Set proxy.enabled: true to honor X-Forwarded-* headers from the listed proxies."
			}
		}
		return ParsedProxyConfig(enabled = false, trustedProxies = emptyList())
	}

	require(config.trusted.isNotEmpty()) {
		"proxy.enabled is true but proxy.trusted is empty. Set trusted to the IP addresses " +
			"or CIDR ranges of your reverse proxies (e.g. [\"127.0.0.1\", \"::1\"] for a " +
			"same-host proxy)."
	}

	val parsed = config.trusted.mapIndexed { index, raw ->
		require(raw.trim() != "*") {
			"proxy.trusted contains \"*\", which would defeat the trust boundary entirely. " +
				"List specific IPs or CIDR ranges instead."
		}
		parseTrustedProxy(raw)
			?: throw IllegalArgumentException(
				"proxy.trusted entry ${index + 1} '$raw' is not a valid IP address or " +
					"CIDR range. Use literal IPs (e.g. \"127.0.0.1\", \"::1\") or CIDR " +
					"ranges (e.g. \"10.0.0.0/24\"). Hostnames are not accepted.",
			)
	}

	return ParsedProxyConfig(enabled = true, trustedProxies = parsed)
}
