package cz.pizavo.omnisign.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.NetworkException
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import cz.pizavo.omnisign.config.OidcProviderConfig
import cz.pizavo.omnisign.config.SsoProviderPreset
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import kotlin.concurrent.thread

/**
 * Unit tests for [IdTokenVerifier].
 *
 * Uses an in-process RSA key pair for signing test tokens and a [JwkProvider] that
 * publishes its public key under the expected `kid`. The
 * [OidcDiscoveryService] is mocked too so no network I/O happens, except in the test of
 * [IdTokenVerifier.defaultJwkProvider], whose JWKS endpoint is a silent loopback socket.
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

    /**
     * A JWKS entry publishing [key] under [kid], naming [issuer] as the issuer it signs for,
     * as Entra ID's entries do, or no issuer.
     */
    fun rsaJwk(kid: String, key: RSAPublicKey, issuer: String? = null): Jwk {
        fun base64Url(value: BigInteger): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        val values = mutableMapOf<String, Any>(
            "kid" to kid,
            "kty" to "RSA",
            "alg" to "RS256",
            "use" to "sig",
            "n" to base64Url(key.modulus),
            "e" to base64Url(key.publicExponent),
        )
        if (issuer != null) values["issuer"] = issuer
        return Jwk.fromValues(values)
    }

    /** A [JwkProvider] that knows only [jwk], under [testKid]. */
    fun jwkProviderOf(jwk: Jwk) = JwkProvider { kid ->
        if (kid == testKid) jwk
        else throw SigningKeyNotFoundException("No JWK with kid '$kid'", null)
    }

    val testJwkProvider = jwkProviderOf(rsaJwk(testKid, publicKey))

    val verifier = IdTokenVerifier(discoveryService, jwkProviderFactory = { _ -> testJwkProvider })

    fun signValid(
        kid: String? = testKid,
        issuer: String = discoveryDoc.issuer,
        audience: String = provider.clientId,
        otherAudiences: List<String> = emptyList(),
        subject: String = "user-123",
        email: String? = "user@example.com",
        algorithm: Algorithm = signingAlgorithm,
        expiresAt: Date? = Date(System.currentTimeMillis() + 60_000),
        issuedAt: Date? = Date(),
        flags: Map<String, Any> = emptyMap(),
        nullClaims: List<String> = emptyList(),
    ): String {
        val builder = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience, *otherAudiences.toTypedArray())
            .withSubject(subject)
        if (expiresAt != null) builder.withExpiresAt(expiresAt)
        if (issuedAt != null) builder.withIssuedAt(issuedAt)
        if (kid != null) builder.withKeyId(kid)
        if (email != null) builder.withClaim("email", email)
        nullClaims.forEach { builder.withNullClaim(it) }
        flags.forEach { (name, value) ->
            when (value) {
                is Boolean -> builder.withClaim(name, value)
                is String -> builder.withClaim(name, value)
                is Int -> builder.withClaim(name, value)
                else -> error("unsupported test claim type for '$name'")
            }
        }
        return builder.sign(algorithm)
    }

    val microsoftProvider = provider.copy(
        name = "microsoft",
        preset = SsoProviderPreset.MICROSOFT,
        tenantId = "contoso.onmicrosoft.com",
    )

    test("verify reads a boolean email_verified claim") {
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to true))).emailVerified shouldBe true
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to false))).emailVerified shouldBe false
    }

    test("verify reads a string email_verified claim in any case, as Cognito and Apple send it") {
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to "TRUE"))).emailVerified shouldBe true
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to "false"))).emailVerified shouldBe false
    }

    test("verify reports no flag when email_verified is absent or JSON null") {
        verifier.verify(provider, signValid()).emailVerified shouldBe null
        verifier.verify(provider, signValid(nullClaims = listOf("email_verified"))).emailVerified shouldBe null
    }

    test("verify reads an email_verified claim holding anything but a recognisable yes or no as no") {
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to "yes"))).emailVerified shouldBe false
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to "0"))).emailVerified shouldBe false
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to 1))).emailVerified shouldBe false
        verifier.verify(provider, signValid(flags = mapOf("email_verified" to 0))).emailVerified shouldBe false
    }

    test("verify reads an xms_edov claim holding anything but a recognisable yes or no as no") {
        verifier.verify(microsoftProvider, signValid(flags = mapOf("xms_edov" to 1))).emailVerified shouldBe false
    }

    test("verify reads xms_edov as the verification flag of a Microsoft token") {
        verifier.verify(microsoftProvider, signValid(flags = mapOf("xms_edov" to true))).emailVerified shouldBe true
        verifier.verify(microsoftProvider, signValid(flags = mapOf("xms_edov" to false))).emailVerified shouldBe false
    }

    test("verify ignores xms_edov in a token of a provider that is not Microsoft") {
        verifier.verify(provider, signValid(flags = mapOf("xms_edov" to true))).emailVerified shouldBe null
    }

    test("verify lets a Microsoft token's xms_edov saying no outweigh email_verified saying yes") {
        val token = signValid(flags = mapOf("email_verified" to true, "xms_edov" to false))

        verifier.verify(microsoftProvider, token).emailVerified shouldBe false
    }

    test("verify lets a Microsoft token's email_verified saying no outweigh xms_edov saying yes") {
        val token = signValid(flags = mapOf("email_verified" to false, "xms_edov" to true))

        verifier.verify(microsoftProvider, token).emailVerified shouldBe false
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

    test("verify rejects an id_token without exp, which would never expire") {
        val ex = shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, signValid(expiresAt = null))
        }
        ex.message shouldContain "exp"
    }

    test("verify rejects an id_token without iat") {
        val ex = shouldThrow<IdTokenVerificationException.VerificationFailed> {
            verifier.verify(provider, signValid(issuedAt = null))
        }
        ex.message shouldContain "iat"
    }

    test("verify accepts an azp claim that names this client") {
        val token = signValid(flags = mapOf("azp" to provider.clientId))

        verifier.verify(provider, token).subject shouldBe "user-123"
    }

    test("verify rejects an azp claim that names another client") {
        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            verifier.verify(provider, signValid(flags = mapOf("azp" to "some-other-client")))
        }
        ex.claim shouldBe "azp"
    }

    test("verify rejects a token for several audiences that carries no azp") {
        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            verifier.verify(provider, signValid(otherAudiences = listOf("some-other-client")))
        }
        ex.claim shouldBe "azp"
    }

    test("verify accepts a token for several audiences whose azp names this client") {
        val token = signValid(
            otherAudiences = listOf("some-other-client"),
            flags = mapOf("azp" to provider.clientId),
        )

        verifier.verify(provider, token).subject shouldBe "user-123"
    }

    test("verify fetches keys from the JWKS URL a refreshed discovery document names, once per URL") {
        val rotatedJwksUri = "https://test-idp.example/jwks-rotated"
        val refreshingDiscovery = mockk<OidcDiscoveryService>().also {
            coEvery { it.discover(any()) } returnsMany listOf(
                discoveryDoc,
                discoveryDoc,
                discoveryDoc.copy(jwksUri = rotatedJwksUri),
            )
        }
        val requestedUris = mutableListOf<String>()
        val refreshingVerifier = IdTokenVerifier(
            refreshingDiscovery,
            jwkProviderFactory = { uri -> requestedUris += uri; testJwkProvider },
        )

        repeat(3) { refreshingVerifier.verify(provider, signValid()) }

        requestedUris shouldBe listOf(discoveryDoc.jwksUri, rotatedJwksUri)
    }

    val tenantTemplate = "https://login.microsoftonline.com/{tenantid}/v2.0"
    val contosoTenant = "aaaabbbb-0000-cccc-1111-dddd2222eeee"
    val fabrikamTenant = "bbbbcccc-1111-dddd-2222-eeee3333ffff"
    val personalAccountsTenant = "9188040d-6c67-4c5b-b112-36a304b66dad"

    /** Entra ID's issuer for [tenant]. */
    fun tenantIssuer(tenant: String) = "https://login.microsoftonline.com/$tenant/v2.0"

    /**
     * A verifier whose discovery document declares [discoveryIssuer], by default Entra ID's
     * multi-tenant template, and whose JWKS publishes the test key as signing for [keyIssuer].
     */
    fun entraVerifier(
        discoveryIssuer: String = tenantTemplate,
        keyIssuer: String? = tenantTemplate,
    ): IdTokenVerifier {
        val entraDiscovery = mockk<OidcDiscoveryService>().also {
            coEvery { it.discover(any()) } returns discoveryDoc.copy(issuer = discoveryIssuer)
        }
        val keys = jwkProviderOf(rsaJwk(testKid, publicKey, keyIssuer))
        return IdTokenVerifier(entraDiscovery, jwkProviderFactory = { _ -> keys })
    }

    /** A token from [tenant] that names [issuer], by default that tenant's, and carries [tid]. */
    fun signFromTenant(tenant: String, issuer: String = tenantIssuer(tenant), tid: String? = tenant): String =
        signValid(issuer = issuer, flags = if (tid == null) emptyMap() else mapOf("tid" to tid))

    test("verify accepts tokens from any tenant under Entra ID's multi-tenant issuer template") {
        val entra = entraVerifier()

        entra.verify(microsoftProvider, signFromTenant(contosoTenant)).issuer shouldBe tenantIssuer(contosoTenant)
        entra.verify(microsoftProvider, signFromTenant(fabrikamTenant)).issuer shouldBe tenantIssuer(fabrikamTenant)
    }

    test("verify rejects a multi-tenant token whose issuer names another tenant than its tid") {
        val ex = shouldThrow<IdTokenVerificationException.VerificationFailed> {
            entraVerifier().verify(microsoftProvider, signFromTenant(contosoTenant, tid = fabrikamTenant))
        }
        ex.message shouldContain "iss"
    }

    test("verify rejects a multi-tenant token that names the issuer template itself") {
        shouldThrow<IdTokenVerificationException.VerificationFailed> {
            entraVerifier().verify(microsoftProvider, signFromTenant(contosoTenant, issuer = tenantTemplate))
        }
    }

    test("verify rejects a multi-tenant token without tid") {
        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            entraVerifier().verify(microsoftProvider, signFromTenant(contosoTenant, tid = null))
        }
        ex.claim shouldBe "tid"
    }

    test("verify rejects a multi-tenant token whose tid is no GUID, so it cannot reshape the issuer") {
        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            entraVerifier().verify(microsoftProvider, signFromTenant("attacker.example/x"))
        }
        ex.claim shouldBe "tid"
    }

    test("verify holds a key Entra ID publishes for personal accounts to their tokens") {
        val entra = entraVerifier(keyIssuer = tenantIssuer(personalAccountsTenant))

        entra.verify(microsoftProvider, signFromTenant(personalAccountsTenant)).issuer shouldBe
            tenantIssuer(personalAccountsTenant)
        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            entra.verify(microsoftProvider, signFromTenant(contosoTenant))
        }
        ex.claim shouldBe "iss"
    }

    test("verify lets a key published for every tenant sign for the one tenant a fixed issuer names") {
        val entra = entraVerifier(discoveryIssuer = tenantIssuer(personalAccountsTenant))

        entra.verify(microsoftProvider, signFromTenant(personalAccountsTenant)).issuer shouldBe
            tenantIssuer(personalAccountsTenant)
    }

    test("verify refuses a key published for every tenant on a token that names no tenant") {
        val entra = entraVerifier(discoveryIssuer = tenantIssuer(contosoTenant))

        val ex = shouldThrow<IdTokenVerificationException.ClaimInvalid> {
            entra.verify(microsoftProvider, signFromTenant(contosoTenant, tid = null))
        }
        ex.claim shouldBe "tid"
    }

    /**
     * Accept one connection on [server] and hold it for [holdMillis] without answering, then
     * stop listening before dropping it. A client without a read timeout therefore fails once
     * the hold ends instead of hanging on the retry `HttpURLConnection` makes after an empty
     * response, which would otherwise wait in the listen backlog forever.
     */
    fun holdSilently(server: ServerSocket, holdMillis: Long): Thread = thread(isDaemon = true) {
        runCatching {
            server.accept().use {
                Thread.sleep(holdMillis)
                server.close()
            }
        }
    }

    test("the default JWKS provider gives up on an endpoint that accepts the connection but never answers") {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val silentPeer = holdSilently(server, holdMillis = 5_000)
            val jwkProvider = IdTokenVerifier.defaultJwkProvider(
                jwksUri = "http://127.0.0.1:${server.localPort}/jwks",
                readTimeoutMillis = 200,
            )

            val failure = shouldThrow<NetworkException> { jwkProvider.get(testKid) }
            failure.cause.shouldBeInstanceOf<SocketTimeoutException>()
            silentPeer.interrupt()
        }
    }
})
