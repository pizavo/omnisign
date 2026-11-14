package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.api.model.responses.SessionResponse

/**
 * In-memory holder for the web target's session tokens.
 *
 * The access token is attached as `Authorization: Bearer …` to every API request; the refresh
 * token is spent against `/auth/refresh` to mint a fresh pair when the access token expires
 * mid-session. Both live only in this object — never in `localStorage`, never in a script-readable
 * cookie — so a single reflected-script injection cannot read them out of persistent storage, and
 * closing the tab drops them. The session itself outlives the tab: the server's HttpOnly refresh
 * cookie, which every tab of the browser shares until the browser closes, lets a reloaded or newly
 * opened app re-establish it on a same-site deployment; elsewhere the user signs in again.
 *
 * The holder also records whose session the tokens are, so [WebAuthApi.refresh] can tell a
 * successor of this tab's session in that shared cookie from a session someone else has started
 * in another tab.
 *
 * The web target is single-threaded (one JS event loop), so the plain `var`s need no
 * synchronization: no two coroutines mutate them at the same instant.
 */
class WebAuthState {

    /** Current access token, or `null` before login / after logout. */
    var accessToken: String? = null
        private set

    /** Current refresh token, or `null` before login / after logout. */
    var refreshToken: String? = null
        private set

    /** The user the tokens belong to, or `null` before login / after logout. */
    var user: SessionResponse? = null
        private set

    /**
     * Record a freshly issued token pair (from `/auth/exchange` or `/auth/refresh`).
     *
     * @param accessToken The new short-lived JWT access token.
     * @param refreshToken The new rotated refresh token.
     * @param user The user the server issued the pair to.
     */
    fun set(accessToken: String, refreshToken: String, user: SessionResponse) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.user = user
    }

    /** Drop both tokens and their user, returning the holder to its logged-out state. */
    fun clear() {
        accessToken = null
        refreshToken = null
        user = null
    }
}
