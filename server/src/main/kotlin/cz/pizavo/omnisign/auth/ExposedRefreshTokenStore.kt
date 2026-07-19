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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite-backed [RefreshTokenStore] implemented with the Exposed SQL framework.
 *
 * Persists refresh tokens in a single `refresh_token_hashes` table; durable across server
 * restarts so legitimate sessions survive deploys. Schema is created on first
 * construction by [initSchema] if not present.
 *
 * Token values are 256-bit cryptographically random byte strings, base64-url-encoded to a
 * 43-character ASCII opaque blob (so an attacker can't distinguish, recover, or iterate
 * tokens). The plaintext token is returned to the client exactly once, by [issue]; only its
 * SHA-256 digest is persisted. [consume] and [delete] hash the presented token and look the
 * digest up, which is an ordinary primary-key match — hashing costs the store nothing and
 * needs no secondary index or side channel.
 *
 * Hashing makes the at-rest copy non-replayable: a leaked `sessions.db`, a backup, or a
 * read-only SQL injection yields digests rather than usable bearer credentials. File
 * permissions on the database file remain the first line of defence; this is the second.
 *
 * Single-use rotation is enforced on the DELETE's row count, not on the preceding SELECT.
 * [consume] returns the principal only when its own DELETE reports a row removed, so two
 * transactions that both read the same row still yield exactly one winner — the second's
 * DELETE matches nothing and it returns `null`. Relying on the SELECT instead would be
 * correct only under SQLite's whole-database write serialization; the DELETE-count check is
 * what keeps rotation single-use under a READ COMMITTED backend (Postgres/MySQL) too, where
 * both readers would otherwise see the row and mint two live successors from one token.
 *
 * No salt and no password-hashing KDF (bcrypt/argon2/PBKDF2), deliberately. Both exist to
 * make *guessing* a low-entropy secret expensive: a salt defeats precomputed tables over a
 * small keyspace, and a KDF's work factor slows brute force over that same small keyspace.
 * A 256-bit uniformly random token has no keyspace worth enumerating and no precomputation
 * to defeat, so both would add cost and complexity while buying nothing — and a per-row
 * salt would additionally destroy the primary-key lookup, since the store would have to
 * scan every row to discover which salt to apply. Plain SHA-256 is the correct primitive
 * for hashing a high-entropy credential.
 *
 * Backend swap: replacing SQLite with Postgres/MySQL/MSSQL is purely a JDBC URL + driver
 * change at the [Database.Companion.connect] call site; this class's code is unchanged.
 *
 * @param database Connected Exposed [Database] handle. The store does not own the
 *   underlying connection pool or close it; lifecycle belongs to the caller (Koin).
 */
class ExposedRefreshTokenStore(private val database: Database) : RefreshTokenStore {

    /**
     * Schema for the `refresh_token_hashes` table.
     *
     * - `token_hash` (PK) — lowercase SHA-256 hex digest of the token handed to the client;
     *   always exactly 64 characters.
     * - `user_id`, `email`, `display_name`, `provider_name` — principal fields copied
     *   verbatim so a refresh can mint a JWT without re-querying any other source.
     * - `auth_time_epoch_seconds` — original SSO authentication time as epoch seconds.
     *   Preserved across rotations so the JWT minted on refresh has the same `auth_time`
     *   claim as the original (see [AuthenticatedPrincipal.authTime]).
     * - `expires_at_epoch_seconds` — token expiry as epoch seconds; rows past this point
     *   are pruned by [pruneExpired] and rejected by [consume].
     */
    private object RefreshTokenHashes : Table("refresh_token_hashes") {
        val tokenHash = varchar("token_hash", TOKEN_HASH_HEX_LENGTH)
        val userId = varchar("user_id", 256)
        val email = varchar("email", 320).nullable()
        val displayName = varchar("display_name", 256).nullable()
        val providerName = varchar("provider_name", 64)
        val authTimeEpochSeconds = long("auth_time_epoch_seconds")
        val expiresAtEpochSeconds = long("expires_at_epoch_seconds")
        override val primaryKey = PrimaryKey(tokenHash)

        init {
            index(false, userId)
            index(false, expiresAtEpochSeconds)
        }
    }

    /**
     * The pre-hashing `refresh_tokens` table, declared only so [initSchema] can drop it.
     *
     * Rows written by the plaintext-token era are unusable after the switch to hashed
     * storage — [consume] looks up a digest, which no plaintext value can ever match — so
     * leaving the table in place would strand dead rows whose only remaining property is
     * that they are replayable bearer credentials sitting at rest. Dropping it is both the
     * migration and the point. Sessions are ephemeral (and capped by
     * [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds] regardless), so the cost
     * is that anyone holding a refresh token across the upgrade signs in again.
     *
     * Carries no columns: `DROP TABLE` needs only the name.
     */
    private object LegacyPlaintextRefreshTokens : Table("refresh_tokens")

    /**
     * Drop the superseded plaintext table and create `refresh_token_hashes` if it does not
     * already exist.
     *
     * Idempotent; safe to call on every server start. Both statements are no-ops once a
     * deployment has started under this schema, and on a fresh install the drop never
     * matches anything.
     */
    fun initSchema() {
        transaction(database) {
            SchemaUtils.drop(LegacyPlaintextRefreshTokens)
            SchemaUtils.create(RefreshTokenHashes)
        }
    }

    override suspend fun issue(principal: AuthenticatedPrincipal, ttl: Duration): RefreshToken =
        withContext(Dispatchers.IO) {
            val token = generateTokenString()
            val expiresAt = Clock.System.now() + ttl
            transaction(database) {
                RefreshTokenHashes.insert {
                    it[tokenHash] = hashToken(token)
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
            val hash = hashToken(token)
            transaction(database) {
                val row = RefreshTokenHashes.selectAll()
                    .where { RefreshTokenHashes.tokenHash eq hash }
                    .singleOrNull()
                    ?: return@transaction null
                if (RefreshTokenHashes.deleteWhere { RefreshTokenHashes.tokenHash eq hash } == 0) {
                    return@transaction null
                }
                val expiresAt = row[RefreshTokenHashes.expiresAtEpochSeconds]
                if (expiresAt < Clock.System.now().epochSeconds) {
                    null
                } else {
                    AuthenticatedPrincipal(
                        userId = row[RefreshTokenHashes.userId],
                        email = row[RefreshTokenHashes.email],
                        displayName = row[RefreshTokenHashes.displayName],
                        providerName = row[RefreshTokenHashes.providerName],
                        authTime = Instant.fromEpochSeconds(row[RefreshTokenHashes.authTimeEpochSeconds]),
                    )
                }
            }
        }

    override suspend fun delete(token: String): Boolean =
        withContext(Dispatchers.IO) {
            val hash = hashToken(token)
            transaction(database) {
                RefreshTokenHashes.deleteWhere { RefreshTokenHashes.tokenHash eq hash } > 0
            }
        }

    override suspend fun deleteAllFor(userId: String): Int =
        withContext(Dispatchers.IO) {
            transaction(database) {
                RefreshTokenHashes.deleteWhere { RefreshTokenHashes.userId eq userId }
            }
        }

    override suspend fun pruneExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().epochSeconds
            transaction(database) {
                val pruned = RefreshTokenHashes.deleteWhere { expiresAtEpochSeconds less now }
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

        /**
         * Width of the `token_hash` column: a SHA-256 digest rendered as lowercase hex is
         * always exactly 64 characters, so the column is sized to the value rather than to
         * a guess with headroom.
         */
        const val TOKEN_HASH_HEX_LENGTH = 64

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

        /**
         * Compute the lowercase SHA-256 hex digest under which [token] is stored.
         *
         * Tokens produced by [generateTokenString] are base64url, hence pure ASCII; an
         * arbitrary client-supplied string is hashed over its UTF-8 bytes, which agrees
         * with the ASCII encoding for every value this store could have issued.
         *
         * @param token Plaintext refresh token as presented by a client, or as just minted.
         * @return The 64-character digest used as the primary key.
         */
        fun hashToken(token: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
