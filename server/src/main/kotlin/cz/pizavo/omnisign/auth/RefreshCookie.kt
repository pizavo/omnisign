package cz.pizavo.omnisign.auth

import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.ApplicationResponse

/**
 * Name of the cookie carrying the refresh token for browser clients.
 */
const val REFRESH_TOKEN_COOKIE: String = "omnisign_refresh"

/**
 * Path the refresh cookie is scoped to.
 *
 * `/auth/refresh` and nothing else. The browser attaches the cookie to the one endpoint whose
 * job is to consume it, and omits it from `/auth/login`, `/auth/session`, `/auth/callback`,
 * `/auth/exchange` and every API route — none of which have any use for it. `/auth/logout` is
 * outside the scope too, and takes the token in its body instead; it can still *clear* the
 * cookie, because a `Set-Cookie` names the path it acts on rather than inheriting the request's.
 */
private const val REFRESH_COOKIE_PATH = "/auth/refresh"

/**
 * Write the refresh cookie carrying [token].
 *
 * Attributes, and why each one:
 *
 * - **`HttpOnly`** — script cannot read it. This does not make the token unreachable from a
 *   compromised page (script can still *call* `/auth/refresh` and be handed a fresh one), but it
 *   does mean the copy that outlives the page is not sitting somewhere `localStorage` would put
 *   it, where a single injection could exfiltrate it verbatim.
 * - **`Secure`** when [secure] — the token must not be readable off the wire. Conditional rather
 *   than unconditional because a `Secure` cookie is dropped wholesale over plain HTTP, which
 *   would silently disable the cookie path for local development; the flag follows the same
 *   `tls != null || proxy.enabled` signal the CORS scheme allowlist uses.
 * - **`SameSite=Lax`** — the cookie rides along with same-site requests, including the app's
 *   `fetch` to the API on a sibling host or a second port, and is withheld from genuinely
 *   cross-site ones. The alternative, `SameSite=None`, is what a cross-site deployment would
 *   need — and is precisely what browsers now block by default as a third-party cookie, so it
 *   would trade a clean fallback for an unreliable one. A cross-site app instead finds no cookie,
 *   gets a `401` from `/auth/refresh`, and re-authenticates through the identity provider; that
 *   path is a redirect the user does not see, and it is the same code path either way.
 * - **No `Max-Age`/`Expires`** — a session cookie, gone when the browser closes. It matches what
 *   the server will honour anyway (`auth.session.maxSessionSeconds` caps a session at 8 hours by
 *   default, so a persistent cookie would mostly be promising time the server would refuse), it
 *   keeps a shared or stolen machine from resuming someone's session, and it keeps the cookie on
 *   the strictly-necessary side of the ePrivacy consent line rather than the "ask first" side.
 * - **Host-only** (no `Domain`) — scoped to the exact host that set it, so a sibling subdomain
 *   cannot read it.
 *
 * The header is rendered directly with `includeEncoding = false` rather than through
 * `response.cookies.append`, which would otherwise tack a `$x-enc=<encoding>` marker attribute onto
 * every `Set-Cookie` (Ktor records the encoding there so its own parser can round-trip non-URI
 * encodings). Browsers ignore that unknown attribute and the cookie works either way, but it is
 * non-standard noise on a browser-facing session cookie, so it is left off. The token is base64url
 * (`A–Z a–z 0–9 - _`), all URI-unreserved, so the default URI encoding is a no-op on the value and
 * [refreshCookie] reads it back unchanged.
 *
 * @param token The plaintext refresh token to hand to the browser.
 * @param secure Whether to set the `Secure` attribute — `true` whenever the deployment terminates
 *   TLS itself or sits behind a proxy that does.
 */
fun ApplicationResponse.setRefreshCookie(token: String, secure: Boolean) {
    headers.append(
        HttpHeaders.SetCookie,
        renderSetCookieHeader(
            name = REFRESH_TOKEN_COOKIE,
            value = token,
            encoding = CookieEncoding.URI_ENCODING,
            path = REFRESH_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
            includeEncoding = false,
        ),
    )
}

/**
 * Clear the refresh cookie.
 *
 * Path and `Secure` must match the values the cookie was set with or the browser treats it as a
 * different cookie and leaves the original in place.
 *
 * @param secure The same value passed to [setRefreshCookie].
 */
fun ApplicationResponse.clearRefreshCookie(secure: Boolean) {
    headers.append(
        HttpHeaders.SetCookie,
        renderSetCookieHeader(
            name = REFRESH_TOKEN_COOKIE,
            value = "",
            encoding = CookieEncoding.URI_ENCODING,
            path = REFRESH_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax"),
            includeEncoding = false,
        ),
    )
}

/**
 * Read the refresh token from the request's cookies.
 *
 * @return The token, or `null` when the cookie is absent or empty — the latter being how a
 *   cleared cookie looks to a browser that has not yet dropped it.
 */
fun ApplicationRequest.refreshCookie(): String? =
    cookies[REFRESH_TOKEN_COOKIE]?.takeIf { it.isNotBlank() }

/**
 * Convenience accessor for [refreshCookie] from a call.
 */
fun ApplicationCall.refreshCookie(): String? = request.refreshCookie()
