package cz.pizavo.omnisign.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*

private val logger = KotlinLogging.logger {}

/**
 * Install Ktor [CallLogging] plugin that logs every HTTP request at INFO level.
 *
 * Each log line includes the HTTP status, method, URI, and the correlation ID
 * provided by [configureCallId] (the `X-Request-Id` value for the call). The ID
 * is also registered in the MDC under key `"request-id"` via [callIdMdc], making
 * it available in all log messages emitted during that request's lifecycle.
 */
fun Application.configureCallLogging() {
	install(CallLogging) {
		callIdMdc("request-id")
		format { call ->
			val status = call.response.status()
			val method = call.request.httpMethod.value
			val uri = call.request.uri
			val id = call.callId ?: "-"
			"$status | $method $uri | id=$id"
		}
	}
}

