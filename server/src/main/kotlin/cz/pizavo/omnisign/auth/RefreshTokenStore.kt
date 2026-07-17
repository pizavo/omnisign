package cz.pizavo.omnisign.auth

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Server-side store of opaque refresh tokens issued alongside JWT access tokens.
 *
 * The refresh token is the long-lived half of the two-token OAuth2-style session model:
 * the JWT access token is short-lived (5 minutes by default) and fully self-contained,
 * while the refresh token is long-lived (30 days by default) and persisted here. Clients
 * never send the refresh token to API routes — only to `/auth/refresh` (to mint a fresh
 * access token + a rotated refresh token) and `/auth/logout` (to invalidate the session).
 *
 * Two pluggability levels:
 *
 * - **Within SQL backends** — the initial implementation
 *   [ExposedRefreshTokenStore] uses Exposed + SQLite; swapping to Postgres/MySQL is a
 *   JDBC URL + driver-dependency change with no code touch.
 * - **Beyond SQL** — implement this interface (e.g., `RedisRefreshTokenStore`) and
 *   change one Koin binding; consumers of the store see no difference.
 *
 * All methods are suspending so backing stores can use coroutine-friendly I/O.
 */
interface RefreshTokenStore {

    /**
     * Mint a new refresh token for [principal] valid for [ttl].
     *
     * Generates a cryptographically random opaque token, persists its binding to the
     * supplied principal fields and computed expiry, and returns the result. This is the
     * only point at which the plaintext token is available — implementations are free to
     * persist a one-way digest of it instead (see [ExposedRefreshTokenStore]), so callers
     * must hand the returned value to the client rather than expecting to read it back.
     * The principal's `authTime` is stored alongside so it can be carried into the JWT
     * minted on the next `/auth/refresh` call (preserving the original SSO
     * authentication time across refreshes — see H-3).
     *
     * @param principal The authenticated user.
     * @param ttl How long the refresh token remains valid before being prunable.
     * @return The persisted [RefreshToken] including its opaque token string.
     */
    suspend fun issue(principal: AuthenticatedPrincipal, ttl: Duration): RefreshToken

    /**
     * Atomically look up [token] and remove it from the store, returning the principal
     * it was bound to.
     *
     * This implements **rotation**: every successful refresh deletes the old token and
     * the caller is expected to immediately [issue] a replacement. A token can therefore
     * be used exactly once; an attacker who replays a captured refresh token loses
     * access the moment the legitimate user refreshes (or vice versa).
     *
     * @param token The opaque refresh token presented by the client.
     * @return The bound [AuthenticatedPrincipal], or `null` when the token is unknown,
     *   expired, or has already been consumed.
     */
    suspend fun consume(token: String): AuthenticatedPrincipal?

    /**
     * Delete [token] from the store without issuing a replacement.
     *
     * Used by `/auth/logout`. Idempotent — deleting an unknown or already-deleted token
     * returns `false` without error.
     *
     * @return `true` when a row was deleted, `false` when the token was not in the store.
     */
    suspend fun delete(token: String): Boolean

    /**
     * Delete every refresh token issued to [userId].
     *
     * Used to implement "log out of all my devices" / forced-revocation flows.
     *
     * @return The number of rows deleted.
     */
    suspend fun deleteAllFor(userId: String): Int

    /**
     * Delete every refresh token whose expiry is in the past.
     *
     * [consume] already rejects an expired row lazily, so this never affects whether a token is
     * accepted — it only bounds how many dead rows the store carries. A background cycle
     * launched at boot (when auth is configured) calls it once immediately and then hourly; see
     * `Application.launchSessionStorePruneIfNeeded`.
     *
     * @return The number of rows pruned.
     */
    suspend fun pruneExpired(): Int
}

/**
 * A refresh token returned by [RefreshTokenStore.issue].
 *
 * @property token The opaque refresh token string returned to the client. 256+ bits of
 *   entropy; treat as a bearer credential.
 * @property expiresAt Instant after which the token is no longer valid; [RefreshTokenStore.consume]
 *   returns `null` once this is in the past.
 */
data class RefreshToken(
    val token: String,
    val expiresAt: Instant,
)
