@file:OptIn(ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.web.auth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * A PKCE verifier/challenge pair for a login hand-off.
 *
 * The app sends [challenge] to the server on `/auth/redirect` and keeps [verifier] to itself,
 * presenting it on `/auth/exchange`. Because only the digest travels outward, an observer of the
 * redirect URL (or of the hand-off code it comes back with) cannot redeem the code — see the
 * server's `HandoffCodeStore`.
 *
 * @property verifier High-entropy random string, base64url. Never leaves the browser.
 * @property challenge `BASE64URL(SHA-256(verifier))` — the value sent as `handoffChallenge`.
 */
data class PkceHandoff(
    val verifier: String,
    val challenge: String,
)

/**
 * Generate a fresh [PkceHandoff] using the browser's Web Crypto API.
 *
 * The verifier is 32 random bytes (`crypto.getRandomValues`) base64url-encoded to 43 characters —
 * the RFC 7636 §4.1 minimum, matching what the server's `PkceService` produces for the OAuth leg —
 * and the challenge is its `SHA-256` digest (`crypto.subtle.digest`, which is asynchronous, hence
 * the suspend). Web Crypto is only present in a secure context (https, or http on localhost), which
 * is exactly where the app is allowed to run: the server rejects a plain-http non-loopback origin.
 *
 * @return The generated pair.
 */
suspend fun generatePkceHandoff(): PkceHandoff {
    val verifier = randomVerifier().toString()
    val challengeJs: JsString = sha256Base64Url(verifier).await()
    return PkceHandoff(verifier = verifier, challenge = challengeJs.toString())
}

/**
 * 32 cryptographically random bytes, base64url-encoded without padding.
 *
 * `=` is stripped rather than matched with a `/=+$/` anchor so the JS carries no `$`, which a
 * Kotlin string template would otherwise try to interpret; base64 padding only ever appears
 * trailing, so removing every `=` is equivalent.
 */
private fun randomVerifier(): JsString =
    js("(function(){var a=new Uint8Array(32);crypto.getRandomValues(a);var s='';for(var i=0;i<a.length;i++){s+=String.fromCharCode(a[i]);}return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=/g,'');})()")

/**
 * `BASE64URL(SHA-256(ASCII(verifier)))`, computed via `crypto.subtle.digest` (a `Promise`).
 */
private fun sha256Base64Url(verifier: String): Promise<JsString> =
    js("crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)).then(function(b){var a=new Uint8Array(b);var s='';for(var i=0;i<a.length;i++){s+=String.fromCharCode(a[i]);}return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=/g,'');})")
