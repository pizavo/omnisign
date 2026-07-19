package cz.pizavo.omnisign.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ExposedLoginRequestStore].
 *
 * Follows [ExposedPkceVerifierStoreTest]'s on-disk-temp-file pattern for the same reason: a
 * per-connection `:memory:` database would defeat Exposed's on-demand connections.
 */
class ExposedLoginRequestStoreTest : FunSpec({

    fun withStore(block: suspend (ExposedLoginRequestStore) -> Unit) {
        val tempDb = File.createTempFile("exposed-login-req-test-", ".db").also { it.delete() }
        try {
            val database = Database.connect(
                "jdbc:sqlite:${tempDb.absolutePath}",
                driver = "org.sqlite.JDBC",
            )
            val store = ExposedLoginRequestStore(database).also { it.initSchema() }
            kotlinx.coroutines.runBlocking { block(store) }
        } finally {
            tempDb.delete()
            File("${tempDb.absolutePath}-journal").delete()
        }
    }

    val sampleRequest = LoginRequest(
        returnTo = "https://omnisign.example.com/",
        handoffChallenge = "challenge-abc",
    )

    test("put then consume returns the stored request") {
        withStore { store ->
            store.put("state-1", sampleRequest, 5.minutes)
            store.consume("state-1") shouldBe sampleRequest
        }
    }

    test("consume is single-use — second call returns null") {
        withStore { store ->
            store.put("state-1", sampleRequest, 5.minutes)
            store.consume("state-1").shouldNotBeNull()
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
            store.put("state-stale", sampleRequest, (-1).seconds)
            store.consume("state-stale").shouldBeNull()
            store.consume("state-stale").shouldBeNull()
        }
    }

    test("pruneExpired deletes only rows whose expiry is in the past") {
        withStore { store ->
            store.put("fresh-state", sampleRequest, 5.minutes)
            store.put("stale-state", sampleRequest, (-1).seconds)

            store.pruneExpired() shouldBe 1

            store.consume("fresh-state").shouldNotBeNull()
            store.consume("stale-state").shouldBeNull()
        }
    }

    test("two different states keep their requests independent") {
        withStore { store ->
            val other = LoginRequest(returnTo = "https://other.example.com/app", handoffChallenge = "challenge-xyz")
            store.put("state-a", sampleRequest, 5.minutes)
            store.put("state-b", other, 5.minutes)

            store.consume("state-a") shouldBe sampleRequest
            store.consume("state-b") shouldBe other
        }
    }
})
