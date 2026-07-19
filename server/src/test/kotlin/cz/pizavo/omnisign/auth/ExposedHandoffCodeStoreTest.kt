package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ExposedHandoffCodeStore].
 *
 * A live [PkceService] over an [ExposedPkceVerifierStore] is used rather than a mock so the
 * challenge/verifier binding is exercised end-to-end — the test derives real S256 challenges the
 * same way a client would, and the store verifies them the same way production does.
 */
class ExposedHandoffCodeStoreTest : FunSpec({

    fun withStore(block: suspend (ExposedHandoffCodeStore) -> Unit) {
        val tempDb = File.createTempFile("exposed-handoff-test-", ".db").also { it.delete() }
        try {
            val database = Database.connect(
                "jdbc:sqlite:${tempDb.absolutePath}",
                driver = "org.sqlite.JDBC",
            )
            val pkce = PkceService(ExposedPkceVerifierStore(database).also { it.initSchema() })
            val store = ExposedHandoffCodeStore(database, pkce).also { it.initSchema() }
            kotlinx.coroutines.runBlocking { block(store) }
        } finally {
            tempDb.delete()
            File("${tempDb.absolutePath}-journal").delete()
        }
    }

    val samplePrincipal = AuthenticatedPrincipal(
        userId = "u1",
        email = "alice@example.com",
        displayName = "Alice",
        providerName = "test",
        authTime = Clock.System.now(),
    )

    /**
     * Produce a matching (verifier, challenge) pair the way a browser client would: a random
     * verifier, and its S256 challenge computed independently of [PkceService] (via raw
     * `MessageDigest`), so the test would catch the store agreeing with a broken derivation.
     */
    fun verifierAndChallenge(): Pair<String, String> {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        return verifier to challenge
    }

    test("consume returns the bound principal when the verifier matches the challenge") {
        withStore { store ->
            val (verifier, challenge) = verifierAndChallenge()
            val code = store.issue(samplePrincipal, challenge, 1.days)

            val redeemed = store.consume(code, verifier)
            redeemed.shouldNotBeNull()
            redeemed.userId shouldBe samplePrincipal.userId
            redeemed.email shouldBe samplePrincipal.email
            redeemed.authTime.epochSeconds shouldBe samplePrincipal.authTime.epochSeconds
        }
    }

    test("consume rejects a verifier that does not match the challenge") {
        withStore { store ->
            val (_, challenge) = verifierAndChallenge()
            val (wrongVerifier, _) = verifierAndChallenge()
            val code = store.issue(samplePrincipal, challenge, 1.days)

            store.consume(code, wrongVerifier).shouldBeNull()
        }
    }

    test("consume leaves the code intact after a wrong verifier, so an observer cannot burn a login") {
        withStore { store ->
            val (verifier, challenge) = verifierAndChallenge()
            val (wrongVerifier, _) = verifierAndChallenge()
            val code = store.issue(samplePrincipal, challenge, 1.days)

            // An observer who captured the code but never held the verifier fires a junk exchange...
            store.consume(code, wrongVerifier).shouldBeNull()
            // ...and the real client, holding the verifier, can still redeem it.
            store.consume(code, verifier).shouldNotBeNull()
        }
    }

    test("consume is single-use — the correct verifier works exactly once") {
        withStore { store ->
            val (verifier, challenge) = verifierAndChallenge()
            val code = store.issue(samplePrincipal, challenge, 1.days)

            store.consume(code, verifier).shouldNotBeNull()
            store.consume(code, verifier).shouldBeNull()
        }
    }

    test("consume returns null for an unknown code") {
        withStore { store ->
            val (verifier, _) = verifierAndChallenge()
            store.consume("never-issued", verifier).shouldBeNull()
        }
    }

    test("consume returns null for an expired code") {
        withStore { store ->
            val (verifier, challenge) = verifierAndChallenge()
            val code = store.issue(samplePrincipal, challenge, (-1).seconds)
            store.consume(code, verifier).shouldBeNull()
        }
    }

    test("issued codes are unique") {
        withStore { store ->
            val (_, challenge) = verifierAndChallenge()
            val first = store.issue(samplePrincipal, challenge, 1.days)
            val second = store.issue(samplePrincipal, challenge, 1.days)
            first shouldNotBe second
        }
    }

    test("the plaintext code is never written to disk") {
        val tempDb = File.createTempFile("exposed-handoff-disk-test-", ".db").also { it.delete() }
        try {
            val database = Database.connect("jdbc:sqlite:${tempDb.absolutePath}", driver = "org.sqlite.JDBC")
            val pkce = PkceService(ExposedPkceVerifierStore(database).also { it.initSchema() })
            val store = ExposedHandoffCodeStore(database, pkce).also { it.initSchema() }
            val (_, challenge) = verifierAndChallenge()
            val code = kotlinx.coroutines.runBlocking { store.issue(samplePrincipal, challenge, 1.days) }

            tempDb.readBytes().toString(Charsets.US_ASCII) shouldNotContain code
        } finally {
            tempDb.delete()
            File("${tempDb.absolutePath}-journal").delete()
        }
    }
})
