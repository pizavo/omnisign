@file:OptIn(ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.web.auth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlinx.browser.window

/**
 * `sessionStorage` key under which the PKCE verifier waits out the identity-provider round trip.
 *
 * `sessionStorage` (not `localStorage`) because the verifier is meaningful for exactly one login
 * attempt in one tab: it is written just before the redirect and read once on return, and it must
 * not linger in another tab or across a browser restart.
 */
private const val VERIFIER_STORAGE_KEY = "omnisign_pkce_verifier"

/**
 * The hand-off code the server appended to the return URL, or `null` when this is an ordinary
 * page load rather than a return from login.
 *
 * @return The `code` query parameter, or `null` when absent or blank.
 */
fun handoffCodeFromUrl(): String? = readCodeParam().toString().takeIf { it.isNotBlank() }

/**
 * Remove the `code` (and any other query/hash) from the address bar without reloading, so a
 * refresh or a shared URL does not carry a spent hand-off code.
 *
 * Uses `history.replaceState` against the origin+path, dropping the query string entirely — the
 * code is the only thing the login flow puts there.
 */
fun clearHandoffCodeFromUrl() {
    replaceUrl(originAndPath().toString())
}

/**
 * Persist [verifier] across the redirect to the identity provider and back.
 *
 * @param verifier The PKCE verifier to reclaim with [takeStoredVerifier] on return.
 */
fun storeVerifier(verifier: String) {
    window.sessionStorage.setItem(VERIFIER_STORAGE_KEY, verifier)
}

/**
 * Reclaim and remove the verifier stored by [storeVerifier].
 *
 * @return The stored verifier, or `null` when none was stored (e.g. the tab was opened directly on
 *   a URL bearing a `code`, without having started the login here).
 */
fun takeStoredVerifier(): String? {
    val verifier = window.sessionStorage.getItem(VERIFIER_STORAGE_KEY)
    window.sessionStorage.removeItem(VERIFIER_STORAGE_KEY)
    return verifier?.takeIf { it.isNotBlank() }
}

/**
 * The URL the login should return the browser to: this app's origin and path, without any query
 * or hash. This is the value sent as `returnTo`, so it must exactly match one of the server's
 * `auth.allowedRedirectUris` entries.
 *
 * @return The absolute origin+path of the current document.
 */
fun returnToUrl(): String = originAndPath().toString()

/**
 * Navigate the browser to [url], leaving the app — used to hand off to the server's
 * `/auth/redirect/{provider}`, which in turn bounces to the identity provider.
 *
 * @param url Absolute URL to navigate to.
 */
fun navigateTo(url: String) {
    assignLocation(url)
}

/**
 * Percent-encode [value] for safe inclusion as a query-parameter value (`encodeURIComponent`).
 *
 * @param value The raw value (a return URL, or a base64url challenge).
 * @return The encoded value.
 */
fun encodeUriComponent(value: String): String = encodeUriComponentJs(value).toString()

private fun encodeUriComponentJs(value: String): JsString = js("encodeURIComponent(value)")

private fun readCodeParam(): JsString =
    js("(new URLSearchParams(window.location.search).get('code') || '')")

private fun originAndPath(): JsString =
    js("(window.location.origin + window.location.pathname)")

private fun replaceUrl(url: String): Unit = js("window.history.replaceState(null, '', url)")

private fun assignLocation(url: String): Unit = js("window.location.assign(url)")
