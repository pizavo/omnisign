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
 *   waiter re-checks whether the token already changed while it waited — if a sibling request
 *   already refreshed, it skips straight to the retry rather than rotating the refresh token a
 *   second time.
 *
 * The refresh call itself goes through [WebAuthApi] (the bare client), never this client, so it
 * cannot recurse back into this interceptor. A refresh that fails (the session is genuinely over —
 * refresh token expired or past `maxSessionSeconds`) leaves the original `401` to propagate, which
 * the repository layer surfaces as an error; the user reloads and lands back on the login gate.
 *
 * @param authState The in-memory token holder the retry reads the refreshed access token from.
 * @param authApi The auth API whose [WebAuthApi.refresh] performs the out-of-band token refresh.
 */
fun authRefreshPlugin(authState: WebAuthState, authApi: WebAuthApi) =
    createClientPlugin("OmniSignAuthRefresh") {
        val refreshMutex = Mutex()

        on(Send) { request ->
            val tokenBeforeSend = authState.accessToken
            val call = proceed(request)
            if (call.response.status != HttpStatusCode.Unauthorized) {
                return@on call
            }

            val refreshed = refreshMutex.withLock {
                if (authState.accessToken != tokenBeforeSend) true else authApi.refresh()
            }
            if (!refreshed) {
                return@on call
            }

            request.headers.remove(HttpHeaders.Authorization)
            authState.accessToken?.let { request.headers.append(HttpHeaders.Authorization, "Bearer $it") }
            proceed(request)
        }
    }
