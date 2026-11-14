package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.api.model.requests.ExchangeCodeRequest
import cz.pizavo.omnisign.api.model.requests.RefreshTokenRequest
import cz.pizavo.omnisign.api.model.responses.LoginOptionsResponse
import cz.pizavo.omnisign.api.model.responses.SessionResponse
import cz.pizavo.omnisign.api.model.responses.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `/auth` endpoints the web client drives directly, outside the repository layer.
 *
 * Deliberately backed by a **bare** [HttpClient] — one with no bearer-token injection and with
 * `expectSuccess` off. Two reasons. First, none of these calls should carry an access token: the
 * whole point of [refresh] and [exchange] is to *obtain* one, and a 401 from them means "no
 * session", not "token expired", so routing them through the reactive-refresh interceptor on the
 * API client would recurse. Second, a non-2xx here is an expected outcome to read — a boot-time
 * [refresh] with no cookie legitimately answers `400` — rather than an exception to catch, so the
 * client is configured to hand back the response instead of throwing.
 *
 * Every successful call writes the fresh token pair, and the user it belongs to, into
 * [authState]; [logout] clears it.
 *
 * @param client A bare Ktor client anchored at the server base URL with JSON negotiation, no
 *   `Authorization` default, and `expectSuccess = false`.
 * @param authState The in-memory token holder this API keeps current.
 */
class WebAuthApi(
    private val client: HttpClient,
    private val authState: WebAuthState,
) {

    /**
     * List the identity providers the server offers for login.
     *
     * @return The providers from `GET /auth/login`, or an empty list when auth is not configured
     *   (`503`) or the request fails — the login screen then simply shows nothing to click, which
     *   is the honest reflection of a server that cannot log anyone in.
     */
    suspend fun loginOptions(): List<LoginOptionsResponse.ProviderInfo> {
        val response = runCatching { client.get("auth/login") }.getOrNull() ?: return emptyList()
        if (response.status != HttpStatusCode.OK) return emptyList()
        return runCatching { response.body<LoginOptionsResponse>().providers }.getOrDefault(emptyList())
    }

    /**
     * Mint a session from a hand-off [code] and its [verifier] (the return leg of a login).
     *
     * @param code The hand-off code the server appended to the return URL.
     * @param verifier The PKCE verifier stashed before the redirect.
     * @return `true` and [authState] populated when `POST /auth/exchange` succeeds; `false`
     *   otherwise (unknown/expired/mismatched code — the app should restart the login).
     */
    suspend fun exchange(code: String, verifier: String): Boolean {
        val response = runCatching {
            client.post("auth/exchange") {
                contentType(ContentType.Application.Json)
                setBody(ExchangeCodeRequest(code = code, codeVerifier = verifier))
            }
        }.getOrNull() ?: return false
        val tokens = readTokens(response) ?: return false
        store(tokens)
        return true
    }

    /**
     * Try to (re)establish a session against `POST /auth/refresh`.
     *
     * Sends the in-memory refresh token in the body when one is held — the mid-session case, which
     * also works cross-site where the cookie is not sent — and otherwise sends no body, letting the
     * server read the HttpOnly refresh cookie. That empty-body form is the boot-time reload-recovery
     * path: an app that just loaded has no in-memory token but may still have a valid cookie.
     *
     * When the server refuses the held token, the refresh is tried once more without a body. Every
     * tab of the app keeps its own copy of the token in memory but shares the one cookie, so
     * opening or reloading the app in another tab spends the token this tab holds when this tab
     * refreshed last, and leaves its successor in the cookie. The second try picks that successor
     * up, but only when the session it resumes belongs to the user this tab was signed in as: a
     * tab never carries on as whoever has signed in elsewhere since, and reports its session over
     * instead. Two tabs refreshing within one round trip of each other can still race for the
     * cookie, and the one that loses is signed out.
     *
     * The refresh runs to the end even when the calling coroutine is cancelled: once the server
     * has rotated the token, dropping its answer would leave this tab holding a spent one.
     *
     * @return [RefreshOutcome.Refreshed] with [authState] repopulated on success;
     *   [RefreshOutcome.SessionOver] when the server answers `401` (unknown / expired / rotated-away
     *   token, or the session cap is exceeded) and the cookie holds no session of the same user, or
     *   when a refresh without a body finds no cookie (`400`); [RefreshOutcome.TransientError] when
     *   the server is unreachable or answers otherwise — the session may still be valid, so the
     *   caller should surface a retryable error rather than sign the user out.
     */
    suspend fun refresh(): RefreshOutcome = withContext(NonCancellable) {
        val heldToken = authState.refreshToken
        val heldUser = authState.user
        val outcome = refreshOnce(heldToken) { true }
        if (heldToken == null || outcome != RefreshOutcome.SessionOver) {
            outcome
        } else {
            refreshOnce(token = null) { user -> heldUser != null && user.isSameUserAs(heldUser) }
        }
    }

    /**
     * Revoke the session server-side and clear the local tokens.
     *
     * Best-effort: the local [authState] is cleared even if the network call fails, so the UI
     * returns to the login screen regardless. Sends the refresh token in the body because the
     * cookie is not scoped to `/auth/logout`.
     */
    suspend fun logout() {
        val token = authState.refreshToken
        runCatching {
            client.post("auth/logout") {
                token?.let {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(it))
                }
            }
        }
        authState.clear()
    }

    /**
     * Post one refresh and store the pair it returns when [accept] takes the user it names.
     *
     * @param token The refresh token to send in the body, or `null` to send none, so the server
     *   reads the cookie.
     * @param accept Whether the session the server hands back may replace the one this tab holds.
     * @return What this attempt alone says about the session; a `400` counts as the end of it only
     *   without a body, since only then does it mean the server found no cookie either.
     */
    private suspend fun refreshOnce(token: String?, accept: (SessionResponse) -> Boolean): RefreshOutcome {
        val response = try {
            client.post("auth/refresh") {
                token?.let {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(it))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return RefreshOutcome.TransientError
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val tokens = readTokens(response) ?: return RefreshOutcome.TransientError
                if (!accept(tokens.user)) return RefreshOutcome.SessionOver
                store(tokens)
                RefreshOutcome.Refreshed
            }

            HttpStatusCode.Unauthorized -> RefreshOutcome.SessionOver
            HttpStatusCode.BadRequest ->
                if (token == null) RefreshOutcome.SessionOver else RefreshOutcome.TransientError

            else -> RefreshOutcome.TransientError
        }
    }

    /**
     * Read the [TokenResponse] of a `200` [response].
     *
     * @return The token pair and its user, or `null` for any other status or an unreadable body.
     */
    private suspend fun readTokens(response: HttpResponse): TokenResponse? {
        if (response.status != HttpStatusCode.OK) return null
        return try {
            response.body<TokenResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Make [tokens] the session this tab holds.
     */
    private fun store(tokens: TokenResponse) {
        authState.set(accessToken = tokens.token, refreshToken = tokens.refreshToken, user = tokens.user)
    }

    /**
     * Whether this user and [other] are the same person at the same identity provider.
     */
    private fun SessionResponse.isSameUserAs(other: SessionResponse): Boolean =
        userId == other.userId && providerName == other.providerName
}
