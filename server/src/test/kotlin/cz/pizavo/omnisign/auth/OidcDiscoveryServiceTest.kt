package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.OidcProviderConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Unit tests for [OidcDiscoveryService]'s TTL-based cache.
 *
 * The cache must (a) skip the network on a fresh hit, (b) refetch after the TTL
 * elapses, (c) serve the stale entry when a refetch fails after we already had one,
 * and (d) propagate the failure when the very first fetch fails (no fallback
 * available). One test per row of that table pins the contract.
 */
class OidcDiscoveryServiceTest : FunSpec({

    val provider = OidcProviderConfig(
        name = "test-idp",
        clientId = "client-id",
        discoveryUrl = "https://idp.example/.well-known/openid-configuration",
        allowedEmailDomains = listOf("*"),
    )

    val discoveryJson = """
        {
          "issuer": "https://idp.example",
          "authorization_endpoint": "https://idp.example/authorize",
          "token_endpoint": "https://idp.example/token",
          "userinfo_endpoint": "https://idp.example/userinfo",
          "jwks_uri": "https://idp.example/jwks"
        }
    """.trimIndent()

    fun mockClient(responder: (callIndex: Int) -> Pair<HttpStatusCode, String?>): Pair<HttpClient, () -> Int> {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount += 1
            val (status, body) = responder(callCount)
            if (body == null) {
                respondError(status)
            } else {
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return client to { callCount }
    }

    test("first discover() call fetches the document from the IdP") {
        val (client, callCount) = mockClient { _ -> HttpStatusCode.OK to discoveryJson }
        val clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000))
        val service = OidcDiscoveryService(client, clock)

        val doc = runBlocking { service.discover(provider) }
        doc.issuer shouldBe "https://idp.example"
        callCount() shouldBe 1
    }

    test("second call within the TTL window returns the cached document without refetching") {
        val (client, callCount) = mockClient { _ -> HttpStatusCode.OK to discoveryJson }
        val clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000))
        val service = OidcDiscoveryService(client, clock)

        runBlocking { service.discover(provider) }
        clock.advance(23.hours)
        runBlocking { service.discover(provider) }

        callCount() shouldBe 1
    }

    test("call after the TTL elapses refetches the document") {
        val (client, callCount) = mockClient { _ -> HttpStatusCode.OK to discoveryJson }
        val clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000))
        val service = OidcDiscoveryService(client, clock)

        runBlocking { service.discover(provider) }
        clock.advance(25.hours)
        runBlocking { service.discover(provider) }

        callCount() shouldBe 2
    }

    test("refetch failure after the TTL serves the stale cached entry and does not throw") {
        val firstResponse = discoveryJson
        val (client, callCount) = mockClient { callIndex ->
            if (callIndex == 1) HttpStatusCode.OK to firstResponse
            else HttpStatusCode.InternalServerError to null
        }
        val clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000))
        val service = OidcDiscoveryService(client, clock)

        val first = runBlocking { service.discover(provider) }
        clock.advance(25.hours)
        val second = runBlocking { service.discover(provider) }

        callCount() shouldBe 2
        second shouldBe first
    }

    test("first-fetch failure with no cached entry propagates the exception") {
        val (client, _) = mockClient { _ -> HttpStatusCode.InternalServerError to null }
        val clock = FixedClock(Instant.fromEpochSeconds(1_700_000_000))
        val service = OidcDiscoveryService(client, clock)

        shouldThrow<Exception> {
            runBlocking { service.discover(provider) }
        }
    }
})

/**
 * Test-only fixed-time [Clock]. Tests advance the clock manually with [advance] to
 * exercise time-dependent code paths without sleeping.
 */
private class FixedClock(private var current: Instant) : Clock {
    override fun now(): Instant = current
    fun advance(duration: Duration) {
        current += duration
    }
}
