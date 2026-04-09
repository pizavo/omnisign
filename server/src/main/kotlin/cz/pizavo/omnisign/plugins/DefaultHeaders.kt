package cz.pizavo.omnisign.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

/**
 * Install Ktor [DefaultHeaders] plugin that appends standard security and branding
 * response headers to every response.
 *
 * Header set:
 * - `X-Powered-By: OmniSign` — branded server identifier.
 * - `X-Content-Type-Options: nosniff` — prevents MIME-type sniffing in browsers.
 * - `X-Frame-Options: DENY` — disables framing (clickjacking protection).
 * - `Referrer-Policy: strict-origin-when-cross-origin` — limits referrer leakage
 *   across origins.
 */
fun Application.configureDefaultHeaders() {
	install(DefaultHeaders) {
		header("X-Powered-By", "OmniSign")
		header("X-Content-Type-Options", "nosniff")
		header("X-Frame-Options", "DENY")
		header("Referrer-Policy", "strict-origin-when-cross-origin")
	}
}

