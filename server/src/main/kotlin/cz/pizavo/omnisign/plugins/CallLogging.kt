package cz.pizavo.omnisign.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*

private val logger = KotlinLogging.logger {}

/**
 * Install Ktor [CallLogging] plugin that logs every HTTP request at INFO level.
 */
fun Application.configureCallLogging() {
	install(CallLogging) {
		format { call ->
			val status = call.response.status()
			val method = call.request.httpMethod.value
			val uri = call.request.uri
			"$status | $method $uri"
		}
	}
}

