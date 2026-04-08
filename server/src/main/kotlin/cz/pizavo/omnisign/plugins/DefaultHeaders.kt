package cz.pizavo.omnisign.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*

/**
 * Install Ktor [DefaultHeaders] plugin that appends standard response headers.
 *
 * Adds `X-Powered-By: OmniSign` to every response, providing a branded server
 * identifier without leaking implementation details.
 */
fun Application.configureDefaultHeaders() {
	install(DefaultHeaders) {
		header("X-Powered-By", "OmniSign")
	}
}

