package cz.pizavo.omnisign.web.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable "is the user signed in" flag that drives the web boot gate reactively.
 *
 * The boot sequence seeds it once with the resolved session outcome; thereafter it is the single
 * switch that swaps the composition between the [App][cz.pizavo.omnisign.App] and the login screen
 * **in place, without a page reload**. Two paths flip it to `false`: the reactive-refresh
 * interceptor when a mid-session refresh comes back [RefreshOutcome.SessionOver], and an explicit
 * sign-out. Because the switch is a [StateFlow] the running composition reacts on the spot, so
 * neither path has to reload the page to reach the login screen.
 *
 * Token lifecycle is deliberately *not* this class's concern: [WebAuthState] owns the tokens, and
 * whoever flips the flag also disposes of them as its case requires — the interceptor drops the
 * now-dead pair, while sign-out leaves them in place for the background `/auth/logout` call to
 * revoke server-side and clear.
 */
class WebSessionState {

    private val _authenticated = MutableStateFlow(false)

    /** `true` while a session is established; the boot gate renders the app, otherwise the login screen. */
    val authenticated: StateFlow<Boolean> = _authenticated.asStateFlow()

    /**
     * Set whether a session is currently established.
     *
     * @param authenticated `true` to render the app, `false` to fall back to the login screen.
     */
    fun set(authenticated: Boolean) {
        _authenticated.value = authenticated
    }
}
