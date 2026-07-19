package cz.pizavo.omnisign.web.auth

import cz.pizavo.omnisign.api.model.requests.ExchangeCodeRequest
import cz.pizavo.omnisign.api.model.requests.RefreshTokenRequest
import cz.pizavo.omnisign.api.model.responses.LoginOptionsResponse
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

/**
 * The `/auth` endpoints the web client drives directly, outside the repository layer.
 *
 * Deliberately backed by a **bare** [HttpClient] — one with no bearer-token injection and with
 * `expectSuccess` off. Two reasons. First, none of these calls should carry an access token: the
 * whole point of [refresh] and [exchange] is to *obtain* one, and a 401 from them means "no
 * session", not "token expired", so routing them through the reactive-refresh interceptor on the
 * API client would recurse. Second, a non-2xx here is an expected outcome to read — a boot-time
 * [refresh] with no cookie legitimately answers 401 — rather than an exception to catch, so the
 * client is configured to hand back the response instead of throwing.
 *
 * Every successful call writes the fresh token pair into [authState]; [logout] clears it.
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
        return storeIfOk(response)
    }

    /**
     * Try to (re)establish a session against `POST /auth/refresh`.
     *
     * Sends the in-memory refresh token in the body when one is held — the mid-session case, which
     * also works cross-site where the cookie is not sent — and otherwise sends no body, letting the
     * server read the HttpOnly refresh cookie. That empty-body form is the boot-time reload-recovery
     * path: an app that just loaded has no in-memory token but may still have a valid cookie.
     *
     * @return [RefreshOutcome.Refreshed] with [authState] repopulated on success;
     *   [RefreshOutcome.SessionOver] when the server answers `401` (unknown / expired / rotated-away
     *   token, or the session cap is exceeded); [RefreshOutcome.TransientError] when the server is
     *   unreachable or answers otherwise — the session may still be valid, so the caller should
     *   surface a retryable error rather than sign the user out.
     */
    suspend fun refresh(): RefreshOutcome {
        val response = runCatching {
            client.post("auth/refresh") {
                authState.refreshToken?.let { token ->
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(token))
                }
            }
        }.getOrNull() ?: return RefreshOutcome.TransientError
        return when {
            storeIfOk(response) -> RefreshOutcome.Refreshed
            response.status == HttpStatusCode.Unauthorized -> RefreshOutcome.SessionOver
            else -> RefreshOutcome.TransientError
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
     * Store the token pair from a `2xx` [TokenResponse]; return whether it was one.
     */
    private suspend fun storeIfOk(response: HttpResponse): Boolean {
        if (response.status != HttpStatusCode.OK) return false
        val tokens = runCatching { response.body<TokenResponse>() }.getOrNull() ?: return false
        authState.set(accessToken = tokens.token, refreshToken = tokens.refreshToken)
        return true
    }
}
