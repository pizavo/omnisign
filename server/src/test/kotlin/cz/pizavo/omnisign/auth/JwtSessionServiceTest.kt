package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.JwtAlgorithmType
import cz.pizavo.omnisign.config.SessionConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

/**
 * Unit tests for [JwtSessionService] covering token issuance, round-trip verification,
 * tamper detection, expiry handling, and algorithm selection.
 */
class JwtSessionServiceTest : FunSpec({

    val secret = "test-secret-minimum-256-bit-key-padding-for-hmac!!"

    val config = SessionConfig(
        algorithm = JwtAlgorithmType.HS512,
        secret = secret,
        issuer = "test-issuer",
        audience = "test-audience",
        tokenExpirySeconds = 3600,
    )
    val service = JwtSessionService(config)

    val principal = AuthenticatedPrincipal(
        userId = "user-123",
        email = "test@example.com",
        displayName = "Test User",
        providerName = "google",
    )

    test("issue returns a non-empty token string") {
        val token = service.issue(principal)
        token.shouldNotBeEmpty()
    }

    test("verify round-trip restores all principal fields") {
        val token = service.issue(principal)
        val restored = service.verify(token)

        restored.shouldNotBeNull()
        restored.userId shouldBe principal.userId
        restored.email shouldBe principal.email
        restored.displayName shouldBe principal.displayName
        restored.providerName shouldBe principal.providerName
    }

    test("verify returns null for a tampered token") {
        val token = service.issue(principal)
        val tampered = token.dropLast(5) + "XXXXX"
        service.verify(tampered).shouldBeNull()
    }

    test("verify returns null for a token signed with a different secret") {
        val otherService = JwtSessionService(config.copy(secret = "completely-different-secret-key-also-long!!"))
        val foreignToken = otherService.issue(principal)
        service.verify(foreignToken).shouldBeNull()
    }

    test("verify returns null for a completely invalid string") {
        service.verify("not.a.jwt").shouldBeNull()
    }

    test("verify returns null for an expired token") {
        val expiredService = JwtSessionService(config.copy(tokenExpirySeconds = -1))
        val expiredToken = expiredService.issue(principal)
        expiredService.verify(expiredToken).shouldBeNull()
    }

    test("principal with null displayName round-trips correctly") {
        val noPicPrincipal = principal.copy(displayName = null)
        val token = service.issue(noPicPrincipal)
        val restored = service.verify(token)

        restored.shouldNotBeNull()
        restored.displayName.shouldBeNull()
    }

    test("HS256 variant round-trips correctly") {
        val hs256Service = JwtSessionService(config.copy(algorithm = JwtAlgorithmType.HS256))
        val token = hs256Service.issue(principal)
        hs256Service.verify(token)?.userId shouldBe principal.userId
    }

    test("HS384 variant round-trips correctly") {
        val hs384Service = JwtSessionService(config.copy(algorithm = JwtAlgorithmType.HS384))
        val token = hs384Service.issue(principal)
        hs384Service.verify(token)?.userId shouldBe principal.userId
    }

    test("token from HS512 is not accepted by HS256 service") {
        val hs256Service = JwtSessionService(config.copy(algorithm = JwtAlgorithmType.HS256))
        val hs512Token = service.issue(principal)
        hs256Service.verify(hs512Token).shouldBeNull()
    }

    test("asymmetric algorithm selection throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            JwtSessionService(config.copy(algorithm = JwtAlgorithmType.ES256))
        }
    }
})
