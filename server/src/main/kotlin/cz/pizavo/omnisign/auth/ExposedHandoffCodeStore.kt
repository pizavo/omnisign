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
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite-backed [HandoffCodeStore] implemented with the Exposed SQL framework.
 *
 * Persists hand-off codes in a single `handoff_codes` table, sharing the Exposed [Database]
 * handle with the other session stores. Schema is created on first construction by [initSchema]
 * if not present.
 *
 * Codes are 256-bit random values generated the same way refresh tokens are, and — like refresh
 * tokens — only their SHA-256 digest is persisted, so the table cannot yield a redeemable
 * credential to anyone who reads it. See [ExposedRefreshTokenStore] for why plain SHA-256 with
 * no salt is the right primitive for a high-entropy value.
 *
 * The principal is copied in verbatim rather than referenced, exactly as
 * [ExposedRefreshTokenStore] does, so redeeming a code needs no second lookup. `auth_time` is
 * carried through so the session that comes out of [PkceService]-bound hand-off is indistinguishable
 * from one issued directly at the callback, including how it ages against
 * [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds].
 *
 * Backend swap: replacing SQLite with Postgres/MySQL/MSSQL is purely a JDBC URL + driver change
 * at the [Database.Companion.connect] call site; this class's code is unchanged.
 *
 * @param database Connected Exposed [Database] handle. The store does not own the underlying
 *   connection pool or close it; lifecycle belongs to the caller (Koin).
 * @param pkceService Used to verify a presented verifier against the stored challenge.
 */
class ExposedHandoffCodeStore(
    private val database: Database,
    private val pkceService: PkceService,
) : HandoffCodeStore {

    /**
     * Schema for the `handoff_codes` table.
     *
     * - `code_hash` (PK) — lowercase SHA-256 hex digest of the code placed in the redirect URL;
     *   always exactly 64 characters.
     * - `handoff_challenge` — base64url SHA-256 digest the redeeming verifier must match.
     * - `user_id`, `email`, `display_name`, `provider_name`, `auth_time_epoch_seconds` —
     *   principal fields copied verbatim, mirroring `refresh_token_hashes`.
     * - `expires_at_epoch_seconds` — code expiry as epoch seconds; rows past this point are
     *   pruned by [pruneExpired] and rejected by [consume].
     */
    private object HandoffCodes : Table("handoff_codes") {
        val codeHash = varchar("code_hash", ExposedRefreshTokenStore.TOKEN_HASH_HEX_LENGTH)
        val handoffChallenge = varchar("handoff_challenge", 128)
        val userId = varchar("user_id", 256)
        val email = varchar("email", 320).nullable()
        val displayName = varchar("display_name", 256).nullable()
        val providerName = varchar("provider_name", 64)
        val authTimeEpochSeconds = long("auth_time_epoch_seconds")
        val expiresAtEpochSeconds = long("expires_at_epoch_seconds")
        override val primaryKey = PrimaryKey(codeHash)

        init {
            index(false, expiresAtEpochSeconds)
        }
    }

    /**
     * Create the `handoff_codes` table if it does not already exist.
     *
     * Idempotent; safe to call on every server start.
     */
    fun initSchema() {
        transaction(database) {
            SchemaUtils.create(HandoffCodes)
        }
    }

    override suspend fun issue(
        principal: AuthenticatedPrincipal,
        handoffChallenge: String,
        ttl: Duration,
    ): String = withContext(Dispatchers.IO) {
        val code = ExposedRefreshTokenStore.generateTokenString()
        val expiresAt = Clock.System.now() + ttl
        transaction(database) {
            HandoffCodes.insert {
                it[codeHash] = ExposedRefreshTokenStore.hashToken(code)
                it[HandoffCodes.handoffChallenge] = handoffChallenge
                it[userId] = principal.userId
                it[email] = principal.email
                it[displayName] = principal.displayName
                it[providerName] = principal.providerName
                it[authTimeEpochSeconds] = principal.authTime.epochSeconds
                it[expiresAtEpochSeconds] = expiresAt.epochSeconds
            }
        }
        code
    }

    override suspend fun consume(code: String, codeVerifier: String): AuthenticatedPrincipal? =
        withContext(Dispatchers.IO) {
            val hash = ExposedRefreshTokenStore.hashToken(code)
            transaction(database) {
                val row = HandoffCodes.selectAll()
                    .where { HandoffCodes.codeHash eq hash }
                    .singleOrNull()
                    ?: return@transaction null

                if (!pkceService.verifyChallenge(codeVerifier, row[HandoffCodes.handoffChallenge])) {
                    logger.warn {
                        "Hand-off code presented with a verifier that does not match its challenge — " +
                            "the code is left intact for whoever holds the real verifier. Either a client " +
                            "bug, or someone who observed the code in transit trying to redeem it without " +
                            "ever having held the verifier."
                    }
                    return@transaction null
                }

                if (HandoffCodes.deleteWhere { HandoffCodes.codeHash eq hash } == 0) {
                    return@transaction null
                }

                if (row[HandoffCodes.expiresAtEpochSeconds] < Clock.System.now().epochSeconds) {
                    null
                } else {
                    AuthenticatedPrincipal(
                        userId = row[HandoffCodes.userId],
                        email = row[HandoffCodes.email],
                        displayName = row[HandoffCodes.displayName],
                        providerName = row[HandoffCodes.providerName],
                        authTime = Instant.fromEpochSeconds(row[HandoffCodes.authTimeEpochSeconds]),
                    )
                }
            }
        }

    override suspend fun pruneExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().epochSeconds
            transaction(database) {
                val pruned = HandoffCodes.deleteWhere { expiresAtEpochSeconds less now }
                if (pruned > 0) {
                    logger.debug { "Pruned $pruned expired hand-off-code row(s)" }
                }
                pruned
            }
        }
}
