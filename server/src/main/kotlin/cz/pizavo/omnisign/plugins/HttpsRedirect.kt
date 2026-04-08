package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ServerConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.httpsredirect.*

/**
 * Install Ktor [HttpsRedirect] plugin when TLS is configured and [ServerConfig.proxyMode] is `false`.
 *
 * In direct TLS mode, HTTP requests hitting the plain connector are automatically redirected
 * to the TLS port with a `301 Moved Permanently` status. When running behind a reverse proxy,
 * the proxy is expected to handle HTTPS enforcement so this plugin is skipped.
 *
 * @param serverConfig Server configuration used to determine TLS and proxy settings.
 */
fun Application.configureHttpsRedirect(serverConfig: ServerConfig) {
	if (serverConfig.proxyMode) return
	if (serverConfig.tls == null) return

	install(HttpsRedirect) {
		sslPort = serverConfig.tlsPort
		permanentRedirect = true
	}
}

