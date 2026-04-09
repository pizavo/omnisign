package cz.pizavo.omnisign.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Install Ktor's [CallId] plugin to track requests with a correlation ID.
 *
 * Behavior:
 * 1. If the incoming request already carries an `X-Request-Id` header, that value
 *    is used as the call ID.
 * 2. Otherwise, a random UUID is generated.
 * 3. The resolved ID is echoed back in the `X-Request-Id` response header so that
 *    clients and reverse proxies can correlate requests to log entries.
 * 4. The ID is also registered under the MDC key `"request-id"` by [configureCallLogging],
 *    making it available in all log lines emitted during the request lifecycle.
 */
fun Application.configureCallId() {
	install(CallId) {
		retrieve { call -> call.request.headers[HttpHeaders.XRequestId] }
		generate {
			@OptIn(ExperimentalUuidApi::class)
			Uuid.generateV7().toString()
		}
		replyToHeader(HttpHeaders.XRequestId)
	}
}

