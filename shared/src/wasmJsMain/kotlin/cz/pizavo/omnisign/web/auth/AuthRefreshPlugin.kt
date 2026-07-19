package cz.pizavo.omnisign.web.auth

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Ktor client plugin that transparently refreshes an expired access token on a `401` and retries
 * the request once.
 *
 * The access token lives ~5 minutes, so a session of any length outlives it; without this, every
 * request issued after the fifth minute would fail until the user reloaded. The plugin hooks the
 * `Send` phase, which sits *inside* the response validator: it therefore observes the raw `401`
 * status directly, before `expectSuccess` turns it into an exception, retries with a fresh token,
 * and lets the validator see the (hopefully `2xx`) retry instead.
 *
 * Two subtleties the implementation has to respect, both structural to Ktor:
 *
 * - **The retried request does not re-run `defaultRequest`.** Only the send pipeline re-executes on
 *   an HttpSend retry, not the request pipeline where `defaultRequest` stamps the `Authorization`
 *   header. So the retried builder still carries the *stale* token; the plugin removes and re-appends
 *   the header itself, or the retry would go out with the just-expired token (or a duplicate).
 * - **Concurrent 401s must refresh once, not once each.** A [Mutex] serialises the refresh, and each
 *   waiter re-checks whether a sibling already installed a *fresh* token (non-null and different)
 *   while it waited — if so it skips straight to the retry rather than rotating the refresh token a
 *   second time. The non-null half matters because [onSessionExpired] clears the token, so a bare
 *   "changed" test would let a dead session masquerade as a completed refresh.
 *
 * The refresh call itself goes through [WebAuthApi] (the bare client), never this client, so it
 * cannot recurse back into this interceptor. Its [RefreshOutcome] then forks the response:
 * [RefreshOutcome.Refreshed] retries the request with the new token; [RefreshOutcome.SessionOver]
 * (the server rejected the refresh token — expired, rotated away, or past `maxSessionSeconds`) fires
 * [onSessionExpired] so the app can switch to its login gate, and lets the original `401` return;
 * [RefreshOutcome.TransientError] (the server was unreachable) simply lets the `401` propagate as an
 * ordinary retryable error, so a momentary blip is never mistaken for a sign-out.
 *
 * @param authState The in-memory token holder the retry reads the refreshed access token from.
 * @param authApi The auth API whose [WebAuthApi.refresh] performs the out-of-band token refresh.
 * @param onSessionExpired Invoked once when a refresh comes back [RefreshOutcome.SessionOver], so the
 *   caller can drop the UI to its login gate. Not called for transient failures.
 */
fun authRefreshPlugin(
    authState: WebAuthState,
    authApi: WebAuthApi,
    onSessionExpired: () -> Unit,
) =
    createClientPlugin("OmniSignAuthRefresh") {
        val refreshMutex = Mutex()

        on(Send) { request ->
            val tokenBeforeSend = authState.accessToken
            val call = proceed(request)
            if (call.response.status != HttpStatusCode.Unauthorized) {
                return@on call
            }

            val outcome = refreshMutex.withLock {
                val current = authState.accessToken
                if (current != null && current != tokenBeforeSend) RefreshOutcome.Refreshed
                else authApi.refresh()
            }
            when (outcome) {
                RefreshOutcome.Refreshed -> {
                    request.headers.remove(HttpHeaders.Authorization)
                    authState.accessToken?.let { request.headers.append(HttpHeaders.Authorization, "Bearer $it") }
                    proceed(request)
                }

                RefreshOutcome.SessionOver -> {
                    onSessionExpired()
                    call
                }

                RefreshOutcome.TransientError -> call
            }
        }
    }
