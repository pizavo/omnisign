package cz.pizavo.omnisign.plugins

import io.ktor.callid.CALL_ID_DEFAULT_DICTIONARY
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
 *    is accepted as the call ID only when it is `1..`[MAX_CALL_ID_LENGTH] characters
 *    long **and** every character is in Ktor's default dictionary `[a-z0-9+/=-]`
 *    ([CALL_ID_DEFAULT_DICTIONARY]).
 * 2. Otherwise (header missing, character outside the dictionary, or length
 *    outside the cap), a random V7 UUID is generated server-side.
 * 3. The resolved ID is echoed back in the `X-Request-Id` response header so that
 *    clients and reverse proxies can correlate requests to log entries.
 * 4. The ID is also registered under the MDC key `"request-id"` by [configureCallLogging],
 *    making it available in all log lines emitted during the request lifecycle.
 *
 * Both checks sit in the one predicate given to `verify`, because Ktor keeps a single
 * verifier: its own dictionary check, installed by default, is replaced by any `verify`
 * call, so a predicate that checked only the length would accept every character. Ktor
 * runs the verifier on the generated ID as well, which a V7 UUID, written in lower-case
 * hex and hyphens, passes.
 *
 * The dictionary keeps a client from writing spaces, `|` or control characters into the
 * access line [configureCallLogging] writes for its request (`status | method uri | id=…`),
 * where they would let it forge fields of that line, and into the MDC. The length cap
 * bounds how much a client can make every log line of its request carry.
 */
fun Application.configureCallId() {
	install(CallId) {
		retrieve { call -> call.request.headers[HttpHeaders.XRequestId] }
		verify { id -> id.length in 1..MAX_CALL_ID_LENGTH && id.all { it in CALL_ID_DEFAULT_DICTIONARY } }
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

