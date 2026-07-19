package cz.pizavo.omnisign.auth

/**
 * The browser-originated half of a login, remembered across the identity-provider round trip.
 *
 * A single-page app starts its login at `GET /auth/redirect/{provider}` and gets it back at
 * `GET /auth/callback/{provider}` — but the callback is issued by the IdP, which echoes only
 * `code` and `state`. Anything the app said on the way out has to be parked server-side under
 * that `state` and picked up again on the way back; this is what gets parked.
 *
 * Both fields are supplied together or not at all. A request carrying neither is an ordinary
 * browser hit on the callback, which answers with the token JSON directly; a request carrying
 * both is a hand-off, which answers with a redirect back to [returnTo]. There is deliberately
 * no in-between: a [returnTo] without a [handoffChallenge] would mean minting an unbound
 * hand-off code, and an unbound code in a URL is a bearer credential in a URL.
 *
 * @property returnTo Absolute URL of the page to send the browser back to once the login
 *   completes. Checked against `auth.allowedRedirectUris` for an exact match before any
 *   redirect is issued — see [isRedirectUriAllowed].
 * @property handoffChallenge Base64url-encoded SHA-256 digest (PKCE `S256`) of a verifier the
 *   app kept to itself. The hand-off code minted at the end of the flow is bound to this, so
 *   the code alone cannot be exchanged for a session.
 */
data class LoginRequest(
    val returnTo: String,
    val handoffChallenge: String,
)
