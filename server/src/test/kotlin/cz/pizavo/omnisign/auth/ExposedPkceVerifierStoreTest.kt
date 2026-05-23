package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ExposedPkceVerifierStore].
 *
 * Mirrors the on-disk-temp-file pattern of [ExposedRefreshTokenStoreTest]: a unique
 * SQLite file per test (deleted in `finally`), not `:memory:` — Exposed opens
 * connections on demand and SQLite's in-memory database is per-connection, so
 * different transactions would see different empty databases.
 */
class ExposedPkceVerifierStoreTest : FunSpec({

    fun withStore(block: suspend (ExposedPkceVerifierStore) -> Unit) {
        val tempDb = File.createTempFile("exposed-pkce-test-", ".db").also { it.delete() }
        try {
            val database = Database.connect(
                "jdbc:sqlite:${tempDb.absolutePath}",
                driver = "org.sqlite.JDBC",
            )
            val store = ExposedPkceVerifierStore(database).also { it.initSchema() }
            kotlinx.coroutines.runBlocking { block(store) }
        } finally {
            tempDb.delete()
            File("${tempDb.absolutePath}-journal").delete()
        }
    }

    test("put then consume returns the stored verifier") {
        withStore { store ->
            store.put("state-1", "verifier-1", 5.minutes)
            store.consume("state-1") shouldBe "verifier-1"
        }
    }

    test("consume is single-use — second call returns null") {
        withStore { store ->
            store.put("state-1", "verifier-1", 5.minutes)
            store.consume("state-1") shouldBe "verifier-1"
            store.consume("state-1").shouldBeNull()
        }
    }

    test("consume returns null for an unknown state") {
        withStore { store ->
            store.consume("never-seen").shouldBeNull()
        }
    }

    test("consume returns null and deletes the row for an expired state") {
        withStore { store ->
            store.put("state-stale", "verifier-stale", (-1).seconds)
            store.consume("state-stale").shouldBeNull()
            store.consume("state-stale").shouldBeNull()
        }
    }

    test("pruneExpired deletes only rows whose expiry is in the past") {
        withStore { store ->
            store.put("fresh-state", "fresh-v", 5.minutes)
            store.put("stale-state", "stale-v", (-1).seconds)

            store.pruneExpired() shouldBe 1

            store.consume("fresh-state").shouldNotBeNull()
            store.consume("stale-state").shouldBeNull()
        }
    }

    test("two different states keep their verifiers independent") {
        withStore { store ->
            store.put("state-a", "verifier-a", 5.minutes)
            store.put("state-b", "verifier-b", 5.minutes)

            store.consume("state-a") shouldBe "verifier-a"
            store.consume("state-b") shouldBe "verifier-b"
        }
    }
})
