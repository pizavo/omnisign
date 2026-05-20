package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Unit tests for [PkceService].
 *
 * Uses an in-memory fake [PkceVerifierStore] so the service can be exercised without
 * SQLite — [ExposedPkceVerifierStoreTest] covers the persistence layer separately.
 */
class PkceServiceTest : FunSpec({

    /**
     * Trivial in-memory [PkceVerifierStore] for use in service-level unit tests.
     *
     * Records every call without enforcing TTL — the service does not consult expiry
     * itself, so TTL behaviour is tested in [ExposedPkceVerifierStoreTest] instead.
     */
    class InMemoryStore : PkceVerifierStore {
        val entries = ConcurrentHashMap<String, String>()
        val putTtls = mutableListOf<Duration>()

        override suspend fun put(state: String, verifier: String, ttl: Duration) {
            entries[state] = verifier
            putTtls += ttl
        }

        override suspend fun consume(state: String): String? = entries.remove(state)
        override suspend fun pruneExpired(): Int = 0
    }

    test("begin returns a 43-character base64url verifier (RFC 7636 §4.1 minimum)") {
        val store = InMemoryStore()
        val service = PkceService(store)

        kotlinx.coroutines.runBlocking { service.begin("state-1") }

        store.entries.size shouldBe 1
        val verifier = store.entries["state-1"]
        verifier shouldNotBe null
        verifier!!.length shouldBe 43
        verifier shouldMatch Regex("^[A-Za-z0-9_-]+$")
    }

    test("begin returns a challenge equal to base64url(sha256(verifier))") {
        val store = InMemoryStore()
        val service = PkceService(store)

        val challenge = kotlinx.coroutines.runBlocking { service.begin("state-1") }

        val storedVerifier = store.entries["state-1"]!!
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(storedVerifier.toByteArray(Charsets.US_ASCII)),
        )
        challenge.challenge shouldBe expected
        challenge.method shouldBe PkceService.METHOD_S256
    }

    test("two begin calls produce different verifiers") {
        val store = InMemoryStore()
        val service = PkceService(store)

        kotlinx.coroutines.runBlocking {
            service.begin("state-1")
            service.begin("state-2")
        }

        store.entries["state-1"] shouldNotBe store.entries["state-2"]
    }

    test("consume delegates to the store and returns the stored verifier") {
        val store = InMemoryStore()
        val service = PkceService(store)

        kotlinx.coroutines.runBlocking {
            val challenge = service.begin("state-1")
            val storedVerifier = store.entries["state-1"]!!

            val consumed = service.consume("state-1")
            consumed shouldBe storedVerifier

            // Round-trip sanity: sha256(consumed) base64url-encoded equals the challenge.
            val rebuilt = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(consumed!!.toByteArray(Charsets.US_ASCII)),
            )
            rebuilt shouldBe challenge.challenge
        }
    }

    test("consume returns null when no flow exists for the given state") {
        val store = InMemoryStore()
        val service = PkceService(store)

        kotlinx.coroutines.runBlocking {
            service.consume("never-seen").shouldBeNull()
        }
    }

    test("begin passes the configured ttl to the store") {
        val store = InMemoryStore()
        val service = PkceService(store, ttl = kotlin.time.Duration.parse("PT7M"))

        kotlinx.coroutines.runBlocking { service.begin("state-1") }

        store.putTtls shouldHaveSize 1
        store.putTtls.single() shouldBe kotlin.time.Duration.parse("PT7M")
    }
})
