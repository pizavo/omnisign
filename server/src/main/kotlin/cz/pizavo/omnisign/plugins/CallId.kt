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
 * 1. If the incoming request already carries an `X-Request-Id` header, the value
 *    is accepted as the call ID only when it passes both Ktor's built-in
 *    character-set verifier (the default dictionary `[a-z0-9+/=-]`) **and** the
 *    length cap of `1..`[MAX_CALL_ID_LENGTH].
 * 2. Otherwise (header missing, character outside the dictionary, or length
 *    outside the cap), a random V7 UUID is generated server-side.
 * 3. The resolved ID is echoed back in the `X-Request-Id` response header so that
 *    clients and reverse proxies can correlate requests to log entries.
 * 4. The ID is also registered under the MDC key `"request-id"` by [configureCallLogging],
 *    making it available in all log lines emitted during the request lifecycle.
 *
 * The default character-set dictionary is the load-bearing CRLF defense on the
 * MDC log-poisoning path — Logback's `MDCConverter` does not sanitize MDC values,
 * so without the dictionary check an attacker could submit
 * `X-Request-Id: foo<CR><LF>INJECTED` and split log lines. Netty's response-header
 * validator catches CRLF only on the *response* write, not on the *MDC populate*,
 * so the dictionary must stay; replacing the implicit default with `verify { true }`
 * re-opens the log-poisoning path. The length cap added here closes the
 * log-storage amplification residual (an attacker submitting megabytes of valid
 * dictionary characters that would otherwise be copied into every log line emitted
 * for that request).
 */
fun Application.configureCallId() {
	install(CallId) {
		retrieve { call -> call.request.headers[HttpHeaders.XRequestId] }
		verify { it.length in 1..MAX_CALL_ID_LENGTH }
		generate {
			@OptIn(ExperimentalUuidApi::class)
			Uuid.generateV7().toString()
		}
		replyToHeader(HttpHeaders.XRequestId)
	}
}

/**
 * Maximum accepted length of a client-supplied `X-Request-Id` header.
 *
 * 64 characters is comfortably above what every common correlation-ID format
 * occupies (UUID v4/v7 with dashes is 36; ULID is 26; Hex-encoded 256-bit IDs are
 * 64) while still bounding the per-request log volume that an attacker can force
 * by stuffing the header with valid dictionary characters.
 */
private const val MAX_CALL_ID_LENGTH = 64

