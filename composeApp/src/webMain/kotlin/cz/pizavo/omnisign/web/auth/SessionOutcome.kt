package cz.pizavo.omnisign.web.auth

/**
 * Result of the boot-time attempt to (re)establish a session, deciding what the app renders.
 *
 * @property authenticated `true` when a session is in hand (the app renders); `false` when the
 *   user must sign in (the login screen renders).
 * @property afterFailedExchange `true` only when this load carried a hand-off code that could not
 *   be redeemed — so the login screen can say the previous attempt did not complete, rather than
 *   presenting a bare first-time prompt. Never `true` when [authenticated] is `true`.
 */
data class SessionOutcome(
    val authenticated: Boolean,
    val afterFailedExchange: Boolean = false,
)
