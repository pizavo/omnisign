package cz.pizavo.omnisign.auth

import kotlin.time.Duration

/**
 * Server-side store of one-time hand-off codes, the credential that carries a completed login
 * from the API origin to the single-page app's origin.
 *
 * The problem it solves: at the end of the authorization-code flow the server holds a session,
 * and the browser is sitting on the API's origin. It has to get to the app's origin with proof
 * of that session, through a redirect — a channel that carries a URL and nothing else. A cookie
 * cannot do it (the app may be cross-site, where the browser will not send one back), and the
 * tokens themselves must not do it (a URL is logged, kept in history, and handed to the next
 * page in `Referer`). So the redirect carries a code that is worthless on its own, and the app
 * trades it for the real tokens over a back channel it controls.
 *
 * Three things keep the code from being a bearer credential in a URL:
 *
 * - **Single use.** [consume] deletes atomically, so a replayed link gets nothing.
 * - **Seconds, not minutes.** The code is minted and redeemed by a page that is already loading;
 *   the TTL covers a redirect, not a human.
 * - **Bound to a verifier.** [consume] requires the pre-image of the challenge supplied at
 *   [issue], which the app generated and kept to itself. This is PKCE (RFC 7636) applied to the
 *   hand-off rather than to the OAuth code — the same reasoning [PkceService] documents for the
 *   authorization code, and for the same reason: a code that leaks is useless without the
 *   verifier, and the verifier never crosses the wire.
 *
 * All methods are suspending so backing stores can use coroutine-friendly I/O.
 */
interface HandoffCodeStore {

    /**
     * Mint a hand-off code for [principal], redeemable once, within [ttl], by whoever can produce
     * the pre-image of [handoffChallenge].
     *
     * @param principal The authenticated user the code stands for.
     * @param handoffChallenge Base64url-encoded SHA-256 digest (PKCE `S256`) of the verifier the
     *   redeeming client holds.
     * @param ttl How long the code remains redeemable.
     * @return The plaintext code to place in the redirect URL. Implementations may persist only a
     *   digest of it, so this is the only point at which the value is available.
     */
    suspend fun issue(principal: AuthenticatedPrincipal, handoffChallenge: String, ttl: Duration): String

    /**
     * Atomically redeem [code], returning the principal it stands for when [codeVerifier] is the
     * pre-image of the challenge it was bound to.
     *
     * The code is consumed (deleted) **only** on a matching verifier. A mismatch leaves the row
     * intact and returns `null`, so a party that merely observed the code in transit — the leak
     * paths a redirect URL is exposed to: browser history, `Referer`, proxy logs — cannot burn a
     * pending login out from under the app that started it by submitting a junk verifier. That is
     * safe against grinding because the challenge's pre-image is a 256-bit random verifier: there
     * is nothing feasible to guess within the code's lifetime, so leaving the row live for the
     * real client to claim costs nothing and denies the observer a denial-of-service.
     *
     * @param code The plaintext code from the redirect URL.
     * @param codeVerifier The verifier whose digest must equal the bound challenge.
     * @return The bound [AuthenticatedPrincipal], or `null` when the code is unknown, expired,
     *   already redeemed, or the verifier does not match.
     */
    suspend fun consume(code: String, codeVerifier: String): AuthenticatedPrincipal?

    /**
     * Delete every code whose expiry is in the past.
     *
     * @return The number of rows pruned.
     */
    suspend fun pruneExpired(): Int
}
