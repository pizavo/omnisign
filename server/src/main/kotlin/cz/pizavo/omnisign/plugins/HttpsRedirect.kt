package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ServerConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.httpsredirect.*

/**
 * Install Ktor [HttpsRedirect] plugin when TLS is configured and reverse-proxy mode is
 * inactive.
 *
 * In direct TLS mode, HTTP requests hitting the plain connector are automatically redirected
 * to the TLS port with a `301 Moved Permanently` status. When running behind a reverse proxy
 * (`proxy.enabled: true`), the proxy is expected to handle HTTPS enforcement so this plugin
 * is skipped.
 *
 * @param serverConfig Server configuration used to determine TLS and proxy settings.
 */
fun Application.configureHttpsRedirect(serverConfig: ServerConfig) {
	if (serverConfig.proxy?.enabled == true) return
	val tls = serverConfig.tls ?: return

	install(HttpsRedirect) {
		sslPort = tls.port
		permanentRedirect = true
	}
}

