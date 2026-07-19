package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ExposedRefreshTokenStore].
 *
 * Each test allocates a unique on-disk SQLite file (deleted in `finally`) rather than
 * `jdbc:sqlite::memory:` because Exposed opens connections on demand and SQLite's
 * `:memory:` database is per-connection — different transactions would see different
 * empty databases, defeating the store's persistence semantics. An on-disk temp file is
 * shared across connections and gives the tests the same durability behavior as
 * production. The journal sidecar (`<file>-journal`) is cleaned up alongside.
 */
class ExposedRefreshTokenStoreTest : FunSpec({

    fun withDatabase(block: suspend (Database, File) -> Unit) {
        val tempDb = File.createTempFile("exposed-store-test-", ".db").also { it.delete() }
        try {
            val database = Database.connect(
                "jdbc:sqlite:${tempDb.absolutePath}",
                driver = "org.sqlite.JDBC",
            )
            kotlinx.coroutines.runBlocking { block(database, tempDb) }
        } finally {
            tempDb.delete()
            File("${tempDb.absolutePath}-journal").delete()
        }
    }

    fun withStore(block: suspend (ExposedRefreshTokenStore) -> Unit) {
        withDatabase { database, _ ->
            block(ExposedRefreshTokenStore(database).also { it.initSchema() })
        }
    }

    val samplePrincipal = AuthenticatedPrincipal(
        userId = "u1",
        email = "alice@example.com",
        displayName = "Alice",
        providerName = "test",
        authTime = Clock.System.now(),
    )

    test("issue returns a unique opaque token and a future expiry") {
        withStore { store ->
            val first = store.issue(samplePrincipal, 1.days)
            val second = store.issue(samplePrincipal, 1.days)
            first.token.shouldNotBeBlank()
            second.token.shouldNotBeBlank()
            first.token shouldNotBe second.token
            (first.expiresAt > Clock.System.now()) shouldBe true
        }
    }

    test("consume returns the bound principal and atomically deletes the row") {
        withStore { store ->
            val issued = store.issue(samplePrincipal, 1.days)

            val firstConsume = store.consume(issued.token)
            firstConsume.shouldNotBeNull()
            firstConsume.userId shouldBe samplePrincipal.userId
            firstConsume.email shouldBe samplePrincipal.email
            firstConsume.displayName shouldBe samplePrincipal.displayName
            firstConsume.providerName shouldBe samplePrincipal.providerName
            firstConsume.authTime.epochSeconds shouldBe samplePrincipal.authTime.epochSeconds

            store.consume(issued.token).shouldBeNull()
        }
    }

    test("consume returns null for an unknown token") {
        withStore { store ->
            store.consume("nope-never-issued").shouldBeNull()
        }
    }

    test("consume returns null and deletes the row for an expired token") {
        withStore { store ->
            val issued = store.issue(samplePrincipal, (-1).seconds)
            store.consume(issued.token).shouldBeNull()
            store.consume(issued.token).shouldBeNull()
        }
    }

    test("delete returns true on hit, false on miss; idempotent") {
        withStore { store ->
            val issued = store.issue(samplePrincipal, 1.days)
            store.delete(issued.token) shouldBe true
            store.delete(issued.token) shouldBe false
            store.delete("never-issued") shouldBe false
        }
    }

    test("deleteAllFor wipes every refresh token for a single userId") {
        withStore { store ->
            store.issue(samplePrincipal, 1.days)
            store.issue(samplePrincipal, 1.days)
            store.issue(samplePrincipal.copy(userId = "u2"), 1.days)

            val deleted = store.deleteAllFor("u1")
            deleted shouldBe 2

            store.deleteAllFor("u1") shouldBe 0
            store.deleteAllFor("u2") shouldBe 1
        }
    }

    test("pruneExpired deletes only rows whose expiry is in the past") {
        withStore { store ->
            val fresh = store.issue(samplePrincipal, 1.days)
            val stale = store.issue(samplePrincipal.copy(userId = "u-stale"), (-1).seconds)

            store.pruneExpired() shouldBe 1
            store.consume(fresh.token).shouldNotBeNull()
            store.consume(stale.token).shouldBeNull()
        }
    }

    /**
     * Asserts the at-rest property directly against the database file rather than against a
     * column the store controls: whatever schema the store settles on, the bytes on disk
     * must not contain a replayable bearer credential. Reading the file as ASCII is enough
     * — the token is base64url and SQLite stores text verbatim, so a plaintext token would
     * appear as a literal substring.
     */
    test("issue writes the token's digest to disk and never its plaintext") {
        withDatabase { database, dbFile ->
            val store = ExposedRefreshTokenStore(database).also { it.initSchema() }
            val issued = store.issue(samplePrincipal, 1.days)

            val onDisk = dbFile.readBytes().toString(Charsets.US_ASCII)
            onDisk shouldNotContain issued.token
            onDisk shouldContain ExposedRefreshTokenStore.hashToken(issued.token)
        }
    }

    /**
     * The plaintext-era `refresh_tokens` table must not survive a boot under the hashed
     * schema. Its rows are already unusable — [ExposedRefreshTokenStore.consume] matches on
     * a digest, which no plaintext value can equal — so the only property they retain is
     * being replayable credentials at rest, which is precisely what hashing removes.
     */
    test("initSchema drops a pre-existing plaintext refresh_tokens table") {
        withDatabase { database, _ ->
            transaction(database) {
                exec("CREATE TABLE refresh_tokens (token VARCHAR(64) NOT NULL PRIMARY KEY)")
                exec("INSERT INTO refresh_tokens (token) VALUES ('plaintext-token-from-a-past-release')")
            }

            ExposedRefreshTokenStore(database).initSchema()

            val survived = transaction(database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'refresh_tokens'") { rs ->
                    rs.next()
                }
            }
            survived shouldBe false
        }
    }
})
