package cz.pizavo.omnisign.web.auth

/**
 * The three distinguishable results of a [WebAuthApi.refresh] attempt.
 *
 * The distinction is load-bearing because a failed refresh has two very different causes that must
 * not be conflated: the session is genuinely over (the server rejected the refresh token) versus the
 * server was momentarily unreachable. Only the former should drop the user to the login gate; a
 * transient blip should surface as an ordinary retryable error, exactly as any other request failure
 * does, so a flaky network never masquerades as a sign-out.
 */
enum class RefreshOutcome {
    /** A fresh token pair was issued and stored; the caller may proceed or retry its request. */
    Refreshed,

    /**
     * The server answered `401` — the refresh token is unknown, expired, rotated away, or the
     * session has passed its maximum lifetime. The session is over and the user must sign in again.
     */
    SessionOver,

    /**
     * The server was unreachable or answered unexpectedly. The session may still be valid, so the
     * caller should surface a retryable error rather than sign the user out.
     */
    TransientError,
}
