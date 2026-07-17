package cz.pizavo.omnisign.api.model.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /auth/exchange` — a single-page app redeeming the hand-off code it was
 * redirected back with, for the session that code stands for.
 *
 * The two fields are the two halves of one proof. [code] arrived in a URL, which is a channel
 * that leaks: it lands in browser history, in the `Referer` sent to whatever the page loads next,
 * and in the access log of anything in between. [codeVerifier] never went anywhere near a URL —
 * the app generated it, sent only its SHA-256 digest with the login request, and kept the
 * original. Presenting both proves the redeemer is the app that started the login rather than
 * whoever came across the code afterwards.
 *
 * This is PKCE (RFC 7636) in its usual shape, applied to the hand-off rather than to the OAuth
 * authorization code — see `HandoffCodeStore` on the server for the full reasoning.
 *
 * @property code The single-use hand-off code taken from the `code` query parameter of the URL
 *   the login redirected back to.
 * @property codeVerifier The high-entropy random string whose base64url SHA-256 digest was sent
 *   as `handoffChallenge` when the login began.
 */
@Serializable
data class ExchangeCodeRequest(
    val code: String,
    val codeVerifier: String,
)
