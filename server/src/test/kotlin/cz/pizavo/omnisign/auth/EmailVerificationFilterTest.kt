package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [isEmailVerificationAccepted], the verified-address requirement of a
 * concrete `allowedEmailDomains` list.
 */
class EmailVerificationFilterTest : FunSpec({

    val concrete = listOf("contoso.com")

    test("an address the IdP marks verified is admitted under a concrete list") {
        isEmailVerificationAccepted(true, concrete, assumeEmailVerified = false) shouldBe true
    }

    test("an address the IdP marks unverified is refused under a concrete list") {
        isEmailVerificationAccepted(false, concrete, assumeEmailVerified = false) shouldBe false
    }

    test("an address the IdP marks unverified is refused even when the provider assumes verification") {
        isEmailVerificationAccepted(false, concrete, assumeEmailVerified = true) shouldBe false
    }

    test("an address the IdP says nothing about is refused under a concrete list") {
        isEmailVerificationAccepted(null, concrete, assumeEmailVerified = false) shouldBe false
    }

    test("an address the IdP says nothing about is admitted when the provider assumes verification") {
        isEmailVerificationAccepted(null, concrete, assumeEmailVerified = true) shouldBe true
    }

    test("the wildcard admits every user whatever the IdP says about the address") {
        listOf(true, false, null).forEach { flag ->
            isEmailVerificationAccepted(flag, listOf("*"), assumeEmailVerified = false) shouldBe true
        }
    }

    test("the wildcard among concrete entries still admits an unverified address") {
        isEmailVerificationAccepted(false, listOf("contoso.com", "*"), assumeEmailVerified = false) shouldBe true
    }
})
