package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * Unit tests for [withIdToken], which folds what a verified id_token says about the user's
 * address into the UserInfo-based [OidcAuthResult].
 */
class OidcAuthResultTest : FunSpec({

    fun userInfoResult(email: String?, emailVerified: Boolean?) = OidcAuthResult(
        principal = AuthenticatedPrincipal(
            userId = "user-123",
            email = email,
            displayName = "Alice",
            providerName = "idp",
            authTime = Instant.fromEpochSeconds(1_700_000_000),
        ),
        claims = buildJsonObject { put("sub", "user-123") },
        emailVerified = emailVerified,
    )

    fun idToken(email: String?, emailVerified: Boolean?) = VerifiedIdToken(
        subject = "user-123",
        email = email,
        issuer = "https://idp.example",
        emailVerified = emailVerified,
    )

    test("without a verified id_token the UserInfo result stands") {
        val result = userInfoResult("alice@contoso.com", emailVerified = true)

        result.withIdToken(null) shouldBeSameInstanceAs result
    }

    test("an id_token without an address leaves the UserInfo address and flag, whatever flag it carries") {
        val result = userInfoResult("alice@contoso.com", emailVerified = null)

        val merged = result.withIdToken(idToken(email = null, emailVerified = true))

        merged.principal.email shouldBe "alice@contoso.com"
        merged.emailVerified.shouldBeNull()
    }

    test("the signed address replaces a different UserInfo one, and the UserInfo flag no longer counts") {
        val result = userInfoResult("mallory@contoso.com", emailVerified = true)

        val merged = result.withIdToken(idToken(email = "alice@fabrikam.com", emailVerified = null))

        merged.principal.email shouldBe "alice@fabrikam.com"
        merged.emailVerified.shouldBeNull()
    }

    test("the signed address with its own flag stands where UserInfo named another") {
        val result = userInfoResult("mallory@contoso.com", emailVerified = false)

        val merged = result.withIdToken(idToken(email = "alice@fabrikam.com", emailVerified = true))

        merged.principal.email shouldBe "alice@fabrikam.com"
        merged.emailVerified shouldBe true
    }

    test("the UserInfo flag counts for the same address written in another case") {
        val result = userInfoResult("Alice@Contoso.com", emailVerified = true)

        val merged = result.withIdToken(idToken(email = "alice@contoso.com", emailVerified = null))

        merged.principal.email shouldBe "alice@contoso.com"
        merged.emailVerified shouldBe true
    }

    test("an id_token marking the address unverified outweighs UserInfo marking it verified") {
        val result = userInfoResult("alice@contoso.com", emailVerified = true)

        result.withIdToken(idToken("alice@contoso.com", emailVerified = false)).emailVerified shouldBe false
    }

    test("UserInfo marking the address unverified outweighs an id_token marking it verified") {
        val result = userInfoResult("alice@contoso.com", emailVerified = false)

        result.withIdToken(idToken("alice@contoso.com", emailVerified = true)).emailVerified shouldBe false
    }

    test("an id_token supplies the address and flag that UserInfo lacked") {
        val result = userInfoResult(email = null, emailVerified = null)

        val merged = result.withIdToken(idToken("alice@contoso.com", emailVerified = true))

        merged.principal.email shouldBe "alice@contoso.com"
        merged.emailVerified shouldBe true
    }

    test("merging keeps the subject, the raw claims and the rest of the principal") {
        val result = userInfoResult("alice@contoso.com", emailVerified = null)

        val merged = result.withIdToken(idToken("alice@contoso.com", emailVerified = true))

        merged.principal shouldBe result.principal
        merged.claims shouldBe result.claims
    }
})
