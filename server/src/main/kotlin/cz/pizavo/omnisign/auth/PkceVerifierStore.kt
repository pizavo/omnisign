package cz.pizavo.omnisign.auth

import kotlin.time.Duration

/**
 * Server-side store of in-flight PKCE (RFC 7636) `code_verifier` values, keyed by the
 * OAuth2 `state` parameter of the corresponding authorization-code flow.
 *
 * Each entry has a short TTL (~5 minutes — see [PkceService]) — long enough to bridge
 * the redirect → IdP → callback hop including user MFA, short enough that abandoned
 * flows do not accumulate.
 *
 * The verifier is generated and stored on `/auth/redirect/{provider}` (so that the
 * matching SHA-256 challenge can be sent on the authorize URL), and atomically
 * consumed on `/auth/callback/{provider}` so it can be forwarded to the IdP's token
 * endpoint as `code_verifier`. The IdP then checks
 * `sha256(code_verifier) == stored_code_challenge` before issuing tokens — binding
 * the authorization code to the entity that originated the flow.
 *
 * Two pluggability levels (same shape as [RefreshTokenStore]):
 * - **Within SQL backends** — [ExposedPkceVerifierStore] uses Exposed + SQLite; swapping
 *   to Postgres/MySQL is a JDBC URL + driver-dependency change.
 * - **Beyond SQL** — implement this interface (e.g., `RedisPkceVerifierStore`) and
 *   change one Koin binding; [PkceService] sees no difference.
 *
 * All methods are suspending so backing stores can use coroutine-friendly I/O.
 */
interface PkceVerifierStore {

    /**
     * Persist [verifier] under [state] with the given [ttl].
     *
     * Called once per redirect-to-IdP step. The matching [consume] call on the callback
     * step looks the verifier up by [state] and atomically removes it.
     *
     * @param state OAuth2 `state` parameter — the natural key (HMAC-signed nonce from
     *   [io.ktor.util.StatelessHmacNonceManager], so it round-trips through the IdP
     *   without forgery).
     * @param verifier The PKCE code verifier to bind to [state] — opaque base64url string.
     * @param ttl How long the entry remains usable before [consume] returns `null`.
     */
    suspend fun put(state: String, verifier: String, ttl: Duration)

    /**
     * Atomically look up [state] and remove it from the store, returning the bound verifier.
     *
     * Single-use by construction — even a successful read deletes the row, so an attacker
     * who replays the callback URL gets nothing on the second hit.
     *
     * @return The stored verifier, or `null` when [state] is unknown, expired, or has
     *   already been consumed.
     */
    suspend fun consume(state: String): String?

    /**
     * Delete every entry whose expiry is in the past.
     *
     * Provided so abandoned flows (where the user closed the tab between redirect and callback)
     * do not accumulate; [consume] already rejects an expired verifier regardless, so this only
     * bounds disk. The boot-time session-store prune cycle calls it once at startup and then
     * hourly when auth is configured (see `Application.launchSessionStorePruneIfNeeded`).
     *
     * @return The number of rows pruned.
     */
    suspend fun pruneExpired(): Int
}
