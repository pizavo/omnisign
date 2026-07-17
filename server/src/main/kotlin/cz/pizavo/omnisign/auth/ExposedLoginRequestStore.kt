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
 * SQLite-backed [LoginRequestStore] implemented with the Exposed SQL framework.
 *
 * Persists in-flight login requests in a single `login_requests` table, sharing the Exposed
 * [Database] handle with [ExposedRefreshTokenStore] and [ExposedPkceVerifierStore]. Durable
 * across server restarts so a login in progress survives a deploy. Schema is created on first
 * construction by [initSchema] if not present.
 *
 * Nothing stored here is a credential: the `return_to` URL is one the operator has allowlisted
 * and the challenge is a SHA-256 digest whose pre-image never leaves the browser. The row's
 * value to an attacker with database access is nil, which is why — unlike
 * [ExposedRefreshTokenStore] — it is stored as-is.
 *
 * Backend swap: replacing SQLite with Postgres/MySQL/MSSQL is purely a JDBC URL + driver change
 * at the [Database.Companion.connect] call site; this class's code is unchanged.
 *
 * @param database Connected Exposed [Database] handle. The store does not own the underlying
 *   connection pool or close it; lifecycle belongs to the caller (Koin).
 */
class ExposedLoginRequestStore(private val database: Database) : LoginRequestStore {

    /**
     * Schema for the `login_requests` table.
     *
     * - `state` (PK) — OAuth2 `state` parameter; HMAC-signed nonce from
     *   [io.ktor.util.StatelessHmacNonceManager]. Sized to match `pkce_verifiers.state`, which
     *   is keyed by the same value.
     * - `return_to` — absolute URL to send the browser back to. Sized for a long URL without
     *   inviting one; the value is exact-matched against `auth.allowedRedirectUris`, so in
     *   practice it is whatever the operator wrote in the config.
     * - `handoff_challenge` — base64url SHA-256 digest, 43 chars. Bounded at RFC 7636 §4.1's
     *   128-char verifier cap, mirroring `pkce_verifiers.verifier`.
     * - `expires_at_epoch_seconds` — flow expiry as epoch seconds; rows past this point are
     *   pruned by [pruneExpired] and rejected by [consume].
     */
    private object LoginRequests : Table("login_requests") {
        val state = varchar("state", 256)
        val returnTo = varchar("return_to", 2048)
        val handoffChallenge = varchar("handoff_challenge", 128)
        val expiresAtEpochSeconds = long("expires_at_epoch_seconds")
        override val primaryKey = PrimaryKey(state)

        init {
            index(false, expiresAtEpochSeconds)
        }
    }

    /**
     * Create the `login_requests` table if it does not already exist.
     *
     * Idempotent; safe to call on every server start.
     */
    fun initSchema() {
        transaction(database) {
            SchemaUtils.create(LoginRequests)
        }
    }

    override suspend fun put(state: String, request: LoginRequest, ttl: Duration) {
        withContext(Dispatchers.IO) {
            val expiresAt = Clock.System.now() + ttl
            transaction(database) {
                LoginRequests.insert {
                    it[LoginRequests.state] = state
                    it[returnTo] = request.returnTo
                    it[handoffChallenge] = request.handoffChallenge
                    it[expiresAtEpochSeconds] = expiresAt.epochSeconds
                }
            }
        }
    }

    override suspend fun consume(state: String): LoginRequest? =
        withContext(Dispatchers.IO) {
            transaction(database) {
                val row = LoginRequests.selectAll()
                    .where { LoginRequests.state eq state }
                    .singleOrNull()
                    ?: return@transaction null
                if (LoginRequests.deleteWhere { LoginRequests.state eq state } == 0) {
                    return@transaction null
                }
                val expiresAt = row[LoginRequests.expiresAtEpochSeconds]
                if (expiresAt < Clock.System.now().epochSeconds) {
                    null
                } else {
                    LoginRequest(
                        returnTo = row[LoginRequests.returnTo],
                        handoffChallenge = row[LoginRequests.handoffChallenge],
                    )
                }
            }
        }

    override suspend fun pruneExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().epochSeconds
            transaction(database) {
                val pruned = LoginRequests.deleteWhere { expiresAtEpochSeconds less now }
                if (pruned > 0) {
                    logger.debug { "Pruned $pruned expired login-request row(s)" }
                }
                pruned
            }
        }
}
