package cz.pizavo.omnisign.data.service

/**
 * Signals that a [ConflatedProbeGate] leader was canceled while coalesced waiters were
 * pending.
 *
 * This is deliberately **not** a [kotlin.coroutines.cancellation.CancellationException]
 * so that waiters from non-canceled coroutine scopes receive a regular, retriable error
 * rather than having their own scope silently canceled by a foreign cancellation.
 *
 * Callers that catch this exception should treat it as a transient failure and retry the
 * operation (e.g., the user can reopen the signing dialog to trigger a fresh discovery).
 *
 * @param message Human-readable description of the cancellation.
 * @param cause The original [kotlin.coroutines.cancellation.CancellationException] from the leader's coroutine.
 */
class LeaderCancelledException(
	message: String,
	cause: Throwable,
) : RuntimeException(message, cause)


