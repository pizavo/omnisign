package cz.pizavo.omnisign.web.auth

/**
 * In-memory holder for the web target's session tokens.
 *
 * The access token is attached as `Authorization: Bearer …` to every API request; the refresh
 * token is spent against `/auth/refresh` to mint a fresh pair when the access token expires
 * mid-session. Both live only in this object — never in `localStorage`, never in a script-readable
 * cookie — so a single reflected-script injection cannot read them out of persistent storage, and
 * closing the tab ends the session. Reload survival is instead the server's HttpOnly refresh
 * cookie's job (same-site deployments); a reloaded app with an empty holder re-establishes its
 * session from that cookie, or falls back to the identity provider.
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

    /**
     * Record a freshly issued token pair (from `/auth/exchange` or `/auth/refresh`).
     *
     * @param accessToken The new short-lived JWT access token.
     * @param refreshToken The new rotated refresh token.
     */
    fun set(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    /** Drop both tokens, returning the holder to its logged-out state. */
    fun clear() {
        accessToken = null
        refreshToken = null
    }
}
