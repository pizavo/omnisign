package cz.pizavo.omnisign.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*

/**
 * Install Ktor [AutoHeadResponse] plugin.
 *
 * Automatically responds to `HEAD` requests for every route that handles `GET`,
 * returning the same headers but without a body. This is required for proper
 * HTTP compliance and is used by monitoring probes and CDN preflight checks.
 */
fun Application.configureAutoHeadResponse() {
	install(AutoHeadResponse)
}

