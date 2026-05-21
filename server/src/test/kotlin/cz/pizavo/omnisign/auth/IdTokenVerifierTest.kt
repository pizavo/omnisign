package cz.pizavo.omnisign.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import cz.pizavo.omnisign.config.OidcProviderConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

/**
 * Unit tests for [IdTokenVerifier].
 *
 * Uses an in-process RSA key pair for signing test tokens and a MockK-stubbed
 * [JwkProvider] that returns the public key for the expected `kid`. The
 * [OidcDiscoveryService] is mocked too so no network I/O happens.
 */
class IdTokenVerifierTest : FunSpec({

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey = keyPair.public as RSAPublicKey
    val privateKey = keyPair.private as RSAPrivateKey
    val signingAlgorithm = Algorithm.RSA256(publicKey, privateKey)
    val testKid = "test-kid-1"

    val provider = OidcProviderConfig(
        name = "test-idp",
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        discoveryUrl = "https://test-idp.example/.well-known/openid-configuration",
        allowedEmailDomains = listOf("*"),
    )

    val discoveryDoc = OidcDiscoveryDocument(
        issuer = "https://test-idp.example",
        authorizationEndpoint = "https://test-idp.example/authorize",
        tokenEndpoint = "https://test-idp.example/token",
        userInfoEndpoint = "https://test-idp.example/userinfo",
        jwksUri = "https://test-idp.example/jwks",
    )

    val discoveryService = mockk<OidcDiscoveryService>().also {
        coEvery { it.discover(any()) } returns discoveryDoc
    }

    val testJwk = mockk<Jwk>().also {
        every { it.publicKey } returns publicKey
    }

    val testJwkProvider = JwkProvider { kid ->
        if (kid == testKid) testJwk
        else throw SigningKeyNotFoundException("No JWK with kid '$kid'", null)
    }

    val verifier = IdTokenVerifier(discoveryService, jwkProviderFactory = { _ -> testJwkProvider })

    fun signValid(
        kid: String? = testKid,
        issuer: String = discoveryDoc.issuer,
        audience: String = provider.clientId,
        subject: String = "user-123",
        email: String? = "user@example.com",
        algorithm: Algorithm = signingAlgorithm,
        expiresAt: Date = Date(System.currentTimeMillis() + 60_000),
    ): String {
        val builder = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withExpiresAt(expiresAt)
            .withIssuedAt(Date())
        if (kid != null) builder.withKeyId(kid)
        if (email != null) builder.withClaim("email", email)
        return builder.sign(algorithm)
    }

    test("verify accepts a well-formed id_token and returns subject + email") {
        val token = signValid()
        val verified = verifier.verify(provider, token)
        verified.subject shouldBe "user-123"
        verified.email shouldBe "user@example.com"
        verified.issuer shouldBe discoveryDoc.issuer
    }

    test("verify rejects a malformed token with Malformed") {
        shouldThrow<IdTokenVerificationException.Malformed> {
            verifier.verify(provider, "this-is-not-a-jwt")
        }
    }

    test("verify rejects an id_token whose JWS header has no kid") {
        val token = signValid(kid = null)
        shouldThrow<IdTokenVerificationException.MissingKid> {
            verifier.verify(provider, token)
        }
    }

    test("verify rejects an id_token signed with a kid that the JWKS does not know") {
        val token = signValid(kid = "unknown-kid")
        val ex = shouldThrow<IdTokenVerificationException.KeyNotFound> {
            verifier.verify(provider, token)
        }
        ex.kid shouldBe "unknown-kid"
    }

    test("verify rejects an id_token whose iss does not match the discovery document") {
        val token = signValid(issuer = "https://attacker.example")
        val ex = shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, token)
        }
        ex.message shouldContain "iss"
    }

    test("verify rejects an id_token whose aud does not contain the client_id") {
        val token = signValid(audience = "some-other-client")
        val ex = shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, token)
        }
        ex.message shouldContain "aud"
    }

    test("verify rejects an expired id_token (beyond the clock-skew leeway)") {
        val token = signValid(expiresAt = Date(System.currentTimeMillis() - 5 * 60_000))
        shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, token)
        }
    }

    test("verify rejects an id_token whose signature was made with a different key") {
        val otherKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val attackerAlg = Algorithm.RSA256(otherKeyPair.public as RSAPublicKey, otherKeyPair.private as RSAPrivateKey)
        val token = signValid(algorithm = attackerAlg)
        shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, token)
        }
    }

    test("verify rejects an HMAC-signed id_token as UnsupportedAlgorithm") {
        val hmacAlg = Algorithm.HMAC256("any-shared-secret")
        val token = signValid(algorithm = hmacAlg)
        val ex = shouldThrow<IdTokenVerificationException.UnsupportedAlgorithm> {
            verifier.verify(provider, token)
        }
        ex.algorithm shouldBe "HS256"
    }
})
