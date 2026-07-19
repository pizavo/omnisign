package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.api.model.responses.LoginOptionsResponse

/**
 * Decide, at boot, whether the app can render or the user must sign in.
 *
 * Runs only when the server reports `authEnabled`. Three paths, in order:
 *
 * 1. **Return from login.** The URL carries a hand-off `code`. The code is cleared from the
 *    address bar immediately (so a reload or shared link cannot replay it), the stashed PKCE
 *    verifier is reclaimed, and the two are exchanged for a session. A missing verifier or a
 *    rejected exchange falls through rather than dead-ending.
 * 2. **Resume.** With no code — or after a failed exchange — a bodyless `/auth/refresh` tries the
 *    HttpOnly refresh cookie, which survives a reload on a same-site deployment. Success means the
 *    returning user never sees the login screen.
 * 3. **Sign in.** Nothing resumed, so the login screen is shown; when this load *had* a code that
 *    failed, that is flagged so the screen can explain rather than present a blank prompt.
 *
 * @param authApi The auth API used to exchange the code and to refresh.
 * @return The outcome the caller renders from.
 */
suspend fun establishSession(authApi: WebAuthApi): SessionOutcome {
    val code = handoffCodeFromUrl()
    if (code != null) {
        clearHandoffCodeFromUrl()
        val verifier = takeStoredVerifier()
        if (verifier != null && authApi.exchange(code, verifier)) {
            return SessionOutcome(authenticated = true)
        }
        if (authApi.refresh() == RefreshOutcome.Refreshed) {
            return SessionOutcome(authenticated = true)
        }
        return SessionOutcome(authenticated = false, afterFailedExchange = true)
    }

    if (authApi.refresh() == RefreshOutcome.Refreshed) {
        return SessionOutcome(authenticated = true)
    }
    return SessionOutcome(authenticated = false)
}

/**
 * Begin an interactive login with [provider]: generate a PKCE hand-off, stash the verifier, and
 * navigate to the server's `/auth/redirect/{provider}` carrying the return URL and challenge.
 *
 * This leaves the app — `navigateTo` is a full browser navigation — so nothing after it runs; the
 * flow resumes when the identity provider bounces the browser back to [returnToUrl] with a `code`,
 * at which point [establishSession] takes over on the fresh page load.
 *
 * @param serverBaseUrl Base URL of the server, or empty for a same-origin deployment.
 * @param provider The chosen provider; its `loginUrl` is the `/auth/redirect/{name}` path.
 */
suspend fun startLogin(serverBaseUrl: String, provider: LoginOptionsResponse.ProviderInfo) {
    val handoff = generatePkceHandoff()
    storeVerifier(handoff.verifier)
    val base = serverBaseUrl.trimEnd('/')
    val target = base + provider.loginUrl +
        "?returnTo=" + encodeUriComponent(returnToUrl()) +
        "&handoffChallenge=" + encodeUriComponent(handoff.challenge)
    navigateTo(target)
}
