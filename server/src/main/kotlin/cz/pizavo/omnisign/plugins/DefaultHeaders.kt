package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.HstsConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

/**
 * Install Ktor [DefaultHeaders] plugin that appends standard security response headers to
 * every response.
 *
 * Header set:
 * - `X-Content-Type-Options: nosniff` — prevents MIME-type sniffing in browsers.
 * - `X-Frame-Options: DENY` — disables framing (clickjacking protection).
 * - `Referrer-Policy: strict-origin-when-cross-origin` — limits referrer leakage
 *   across origins.
 * - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` — appropriate
 *   for a REST API that never serves HTML; `frame-ancestors 'none'` is the modern
 *   equivalent of `X-Frame-Options: DENY` for CSP-aware browsers.
 * - `Strict-Transport-Security` — only added when [hstsConfig] is non-null. The value is
 *   built from [HstsConfig.maxAgeSeconds], [HstsConfig.includeSubDomains], and
 *   [HstsConfig.preload]. Must only be enabled when the server is reachable exclusively
 *   over HTTPS.
 *
 * No `X-Powered-By` header by deliberate choice — it was a branding artifact, never a
 * security control. The practical risk of advertising the stack name is small (route
 * paths, the `omnisign` JWT issuer, and `X-OmniSign-Result` already reveal the product
 * to anyone who looks), but the header was also an unambiguous self-identification an
 * operator never opted into, so dropping it is free defense in depth.
 *
 * @param hstsConfig HSTS configuration, or `null` to omit the header entirely.
 */
fun Application.configureDefaultHeaders(hstsConfig: HstsConfig? = null) {
	install(DefaultHeaders) {
		header("X-Content-Type-Options", "nosniff")
		header("X-Frame-Options", "DENY")
		header("Referrer-Policy", "strict-origin-when-cross-origin")
		header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		if (hstsConfig != null) {
			header("Strict-Transport-Security", buildHstsValue(hstsConfig))
		}
	}
}

/**
 * Build the `Strict-Transport-Security` header value from [config].
 */
private fun buildHstsValue(config: HstsConfig): String = buildString {
	append("max-age=${config.maxAgeSeconds}")
	if (config.includeSubDomains) append("; includeSubDomains")
	if (config.preload) append("; preload")
}
