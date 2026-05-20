package cz.pizavo.omnisign.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite-backed [RefreshTokenStore] implemented with the Exposed SQL framework.
 *
 * Persists refresh tokens in a single `refresh_tokens` table; durable across server
 * restarts so legitimate sessions survive deploys. Schema is created on first
 * construction by [initSchema] if not present.
 *
 * Token values are 256-bit cryptographically random byte strings, base64-url-encoded
 * to a 43-character ASCII opaque blob (so an attacker can't distinguish, recover, or
 * iterate tokens). Token storage is plaintext — these are bearer credentials that the
 * server itself must compare against, so hashing them would require a side channel for
 * the original lookup. File permissions on the SQLite file (`sessions.db`, set to 0600
 * on Unix) are the at-rest protection.
 *
 * Backend swap: replacing SQLite with Postgres/MySQL/MSSQL is purely a JDBC URL + driver
 * change at the [Database.Companion.connect] call site; this class's code is unchanged.
 *
 * @param database Connected Exposed [Database] handle. The store does not own the
 *   underlying connection pool or close it; lifecycle belongs to the caller (Koin).
 */
class ExposedRefreshTokenStore(private val database: Database) : RefreshTokenStore {

    /**
     * Schema for the `refresh_tokens` table.
     *
     * - `token` (PK) — base64-url-encoded 32-byte random string, ~43 chars; bounded
     *   to 64 to leave headroom if the encoding format ever changes.
     * - `user_id`, `email`, `display_name`, `provider_name` — principal fields copied
     *   verbatim so a refresh can mint a JWT without re-querying any other source.
     * - `auth_time_epoch_seconds` — original SSO authentication time as epoch seconds.
     *   Preserved across rotations so the JWT minted on refresh has the same `auth_time`
     *   claim as the original (see [AuthenticatedPrincipal.authTime]).
     * - `expires_at_epoch_seconds` — token expiry as epoch seconds; rows past this point
     *   are pruned by [pruneExpired] and rejected by [consume].
     */
    private object RefreshTokens : Table("refresh_tokens") {
        val token = varchar("token", 64)
        val userId = varchar("user_id", 256)
        val email = varchar("email", 320)
        val displayName = varchar("display_name", 256).nullable()
        val providerName = varchar("provider_name", 64)
        val authTimeEpochSeconds = long("auth_time_epoch_seconds")
        val expiresAtEpochSeconds = long("expires_at_epoch_seconds")
        override val primaryKey = PrimaryKey(token)

        init {
            index(false, userId)
            index(false, expiresAtEpochSeconds)
        }
    }

    /**
     * Create the `refresh_tokens` table if it does not already exist.
     *
     * Idempotent; safe to call on every server start.
     */
    fun initSchema() {
        transaction(database) {
            SchemaUtils.create(RefreshTokens)
        }
    }

    override suspend fun issue(principal: AuthenticatedPrincipal, ttl: Duration): RefreshToken =
        withContext(Dispatchers.IO) {
            val token = generateTokenString()
            val expiresAt = Clock.System.now() + ttl
            transaction(database) {
                RefreshTokens.insert {
                    it[RefreshTokens.token] = token
                    it[userId] = principal.userId
                    it[email] = principal.email
                    it[displayName] = principal.displayName
                    it[providerName] = principal.providerName
                    it[authTimeEpochSeconds] = principal.authTime.epochSeconds
                    it[expiresAtEpochSeconds] = expiresAt.epochSeconds
                }
            }
            RefreshToken(token = token, expiresAt = expiresAt)
        }

    override suspend fun consume(token: String): AuthenticatedPrincipal? =
        withContext(Dispatchers.IO) {
            transaction(database) {
                val row = RefreshTokens.selectAll()
                    .where { RefreshTokens.token eq token }
                    .singleOrNull()
                    ?: return@transaction null
                val expiresAt = row[RefreshTokens.expiresAtEpochSeconds]
                RefreshTokens.deleteWhere { RefreshTokens.token eq token }
                if (expiresAt < Clock.System.now().epochSeconds) {
                    null
                } else {
                    AuthenticatedPrincipal(
                        userId = row[RefreshTokens.userId],
                        email = row[RefreshTokens.email],
                        displayName = row[RefreshTokens.displayName],
                        providerName = row[RefreshTokens.providerName],
                        authTime = Instant.fromEpochSeconds(row[RefreshTokens.authTimeEpochSeconds]),
                    )
                }
            }
        }

    override suspend fun delete(token: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction(database) {
                RefreshTokens.deleteWhere { RefreshTokens.token eq token } > 0
            }
        }

    override suspend fun deleteAllFor(userId: String): Int =
        withContext(Dispatchers.IO) {
            transaction(database) {
                RefreshTokens.deleteWhere { RefreshTokens.userId eq userId }
            }
        }

    override suspend fun pruneExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().epochSeconds
            transaction(database) {
                val pruned = RefreshTokens.deleteWhere { expiresAtEpochSeconds less now }
                if (pruned > 0) {
                    logger.debug { "Pruned $pruned expired refresh-token row(s)" }
                }
                pruned
            }
        }

    companion object {
        /**
         * Length, in bytes, of the random portion of each generated refresh token.
         * 32 bytes = 256 bits, well above brute-force-feasibility for a bearer credential.
         */
        const val TOKEN_RANDOM_BYTES = 32

        private val secureRandom = SecureRandom()

        /**
         * Generate a 256-bit random opaque token suitable for use as a refresh token.
         *
         * Output is base64-url-encoded (no padding) for a 43-character ASCII string
         * safe to use in HTTP bodies and headers without escaping.
         */
        fun generateTokenString(): String {
            val bytes = ByteArray(TOKEN_RANDOM_BYTES).also { secureRandom.nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
