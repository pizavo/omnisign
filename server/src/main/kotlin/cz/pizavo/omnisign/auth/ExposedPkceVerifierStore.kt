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
import kotlin.time.Clock
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * SQLite-backed [PkceVerifierStore] implemented with the Exposed SQL framework.
 *
 * Persists in-flight PKCE code verifiers in a single `pkce_verifiers` table, sharing
 * the Exposed [Database] handle with [ExposedRefreshTokenStore]. Durable across server
 * restarts so an OAuth flow in progress survives a deploy. Schema is created on first
 * construction by [initSchema] if not present.
 *
 * Each row carries a 43-character base64url-encoded verifier (RFC 7636 §4.1 minimum
 * length), keyed by the OAuth2 `state` parameter (HMAC-signed via
 * [io.ktor.util.StatelessHmacNonceManager]). On [consume] the row is deleted atomically
 * inside the same transaction as the lookup, so the verifier is genuinely single-use —
 * a replayed callback URL gets nothing on the second hit.
 *
 * On-disk verifier exposure: the sessions database file is created with the
 * default umask (typically `0600` on Linux deployments). Combined with the 5-minute
 * TTL and single-use semantics this puts the at-rest exposure window in the same
 * order as in-memory storage; the threat models differ only when an attacker has
 * file-system read access without process memory access, which is unusual.
 *
 * Backend swap: replacing SQLite with Postgres/MySQL/MSSQL is purely a JDBC URL +
 * driver change at the [Database.Companion.connect] call site; this class's code is
 * unchanged.
 *
 * @param database Connected Exposed [Database] handle. The store does not own the
 *   underlying connection pool or close it; lifecycle belongs to the caller (Koin).
 */
class ExposedPkceVerifierStore(private val database: Database) : PkceVerifierStore {

    /**
     * Schema for the `pkce_verifiers` table.
     *
     * - `state` (PK) — OAuth2 `state` parameter; HMAC-signed nonce from
     *   [io.ktor.util.StatelessHmacNonceManager]. Sized to 256 chars to leave headroom
     *   over Ktor's default ~64-char nonces.
     * - `verifier` — base64url-encoded random string. RFC 7636 §4.1 caps verifiers at
     *   128 chars; this column is sized accordingly.
     * - `expires_at_epoch_seconds` — flow expiry as epoch seconds; rows past this point
     *   are pruned by [pruneExpired] and rejected by [consume].
     */
    private object PkceVerifiers : Table("pkce_verifiers") {
        val state = varchar("state", 256)
        val verifier = varchar("verifier", 128)
        val expiresAtEpochSeconds = long("expires_at_epoch_seconds")
        override val primaryKey = PrimaryKey(state)

        init {
            index(false, expiresAtEpochSeconds)
        }
    }

    /**
     * Create the `pkce_verifiers` table if it does not already exist.
     *
     * Idempotent; safe to call on every server start.
     */
    fun initSchema() {
        transaction(database) {
            SchemaUtils.create(PkceVerifiers)
        }
    }

    override suspend fun put(state: String, verifier: String, ttl: Duration) {
        withContext(Dispatchers.IO) {
            val expiresAt = Clock.System.now() + ttl
            transaction(database) {
                PkceVerifiers.insert {
                    it[PkceVerifiers.state] = state
                    it[PkceVerifiers.verifier] = verifier
                    it[expiresAtEpochSeconds] = expiresAt.epochSeconds
                }
            }
        }
    }

    override suspend fun consume(state: String): String? =
        withContext(Dispatchers.IO) {
            transaction(database) {
                val row = PkceVerifiers.selectAll()
                    .where { PkceVerifiers.state eq state }
                    .singleOrNull()
                    ?: return@transaction null
                if (PkceVerifiers.deleteWhere { PkceVerifiers.state eq state } == 0) {
                    return@transaction null
                }
                val expiresAt = row[PkceVerifiers.expiresAtEpochSeconds]
                if (expiresAt < Clock.System.now().epochSeconds) {
                    null
                } else {
                    row[PkceVerifiers.verifier]
                }
            }
        }

    override suspend fun pruneExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().epochSeconds
            transaction(database) {
                val pruned = PkceVerifiers.deleteWhere { expiresAtEpochSeconds less now }
                if (pruned > 0) {
                    logger.debug { "Pruned $pruned expired PKCE verifier row(s)" }
                }
                pruned
            }
        }
}
