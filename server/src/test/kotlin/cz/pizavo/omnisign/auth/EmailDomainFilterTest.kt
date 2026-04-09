package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [isEmailDomainAllowed].
 */
class EmailDomainFilterTest : FunSpec({

    test("returns true when allowedDomains is null") {
        isEmailDomainAllowed("user@anydomain.com", null) shouldBe true
    }

    test("returns true when allowedDomains is empty") {
        isEmailDomainAllowed("user@anydomain.com", emptyList()) shouldBe true
    }

    test("returns true when email domain matches a single allowed domain") {
        isEmailDomainAllowed("alice@contoso.com", listOf("contoso.com")) shouldBe true
    }

    test("returns true when email domain matches one of multiple allowed domains") {
        isEmailDomainAllowed("bob@fabrikam.com", listOf("contoso.com", "fabrikam.com")) shouldBe true
    }

    test("returns false when email domain does not match any allowed domain") {
        isEmailDomainAllowed("eve@gmail.com", listOf("contoso.com", "fabrikam.com")) shouldBe false
    }

    test("domain comparison is case-insensitive") {
        isEmailDomainAllowed("user@CONTOSO.COM", listOf("contoso.com")) shouldBe true
        isEmailDomainAllowed("user@contoso.com", listOf("CONTOSO.COM")) shouldBe true
    }

    test("returns false when email has no at-sign") {
        isEmailDomainAllowed("notanemail", listOf("contoso.com")) shouldBe false
    }

    test("returns false when email domain is empty after at-sign") {
        isEmailDomainAllowed("user@", listOf("contoso.com")) shouldBe false
    }

    test("subdomain is not matched by parent domain entry") {
        isEmailDomainAllowed("user@mail.contoso.com", listOf("contoso.com")) shouldBe false
    }

    test("returns true for subdomain when subdomain is explicitly listed") {
        isEmailDomainAllowed("user@mail.contoso.com", listOf("mail.contoso.com")) shouldBe true
    }
})

