package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ServerConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.forwardedheaders.*

/**
 * Install Ktor [ForwardedHeaders] plugin when [ServerConfig.proxyMode] is enabled.
 *
 * In proxy mode, `X-Forwarded-For`, `X-Forwarded-Proto`, and `X-Forwarded-Host` headers
 * from the reverse proxy are trusted so that the application sees the original client address
 * and protocol.
 *
 * @param proxyMode Whether the server is behind a reverse proxy.
 */
fun Application.configureForwardedHeaders(proxyMode: Boolean) {
	if (!proxyMode) return
	install(XForwardedHeaders)
}

