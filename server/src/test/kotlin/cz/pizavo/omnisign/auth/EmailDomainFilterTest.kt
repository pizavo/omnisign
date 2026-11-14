package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [isEmailDomainAllowed].
 */
class EmailDomainFilterTest : FunSpec({

    test("returns true for any email when allowedDomains contains the \"*\" wildcard") {
        isEmailDomainAllowed("user@anydomain.com", listOf("*")) shouldBe true
        isEmailDomainAllowed("user@another.example", listOf("*")) shouldBe true
    }

    test("returns true when \"*\" is one of several entries (wildcard wins)") {
        isEmailDomainAllowed("eve@unlisted.com", listOf("contoso.com", "*")) shouldBe true
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

    test("null email under the \"*\" wildcard is admitted (allow-any-authenticated-user)") {
        isEmailDomainAllowed(null, listOf("*")) shouldBe true
    }

    test("null email under \"*\" mixed with concrete entries is still admitted") {
        isEmailDomainAllowed(null, listOf("contoso.com", "*")) shouldBe true
    }

    test("null email under concrete domains is rejected (fail closed)") {
        isEmailDomainAllowed(null, listOf("contoso.com")) shouldBe false
        isEmailDomainAllowed(null, listOf("contoso.com", "fabrikam.com")) shouldBe false
    }

    test("a lookalike domain with a dotless i does not match the ASCII entry") {
        isEmailDomainAllowed("user@fabrıkam.com", listOf("fabrikam.com")) shouldBe false
    }

    test("a lookalike domain with a dotted capital I does not match the ASCII entry") {
        isEmailDomainAllowed("user@fabrİkam.com", listOf("fabrikam.com")) shouldBe false
    }

    test("an internationalised entry matches the address's xn-- spelling of the same domain") {
        isEmailDomainAllowed("user@xn--bcher-kva.de", listOf("bücher.de")) shouldBe true
    }

    test("an xn-- entry matches the address's internationalised spelling of the same domain") {
        isEmailDomainAllowed("user@BÜCHER.de", listOf("xn--bcher-kva.de")) shouldBe true
    }

    test("an entry that is no valid host name matches nothing") {
        isEmailDomainAllowed("user@contoso.com", listOf("@contoso.com")) shouldBe false
    }

    test("a domain with ß does not pass for the ASCII domain IDNA2003 would turn it into") {
        isEmailDomainAllowed("user@claß.com", listOf("class.com")) shouldBe false
        isEmailDomainAllowed("user@CLAß.com", listOf("class.com")) shouldBe false
    }

    test("an entry with ß admits nobody of the ASCII domain IDNA2003 would turn it into") {
        isEmailDomainAllowed("user@class.com", listOf("claß.com")) shouldBe false
    }

    test("a domain with a Greek final sigma does not pass for its plain-sigma spelling") {
        isEmailDomainAllowed("user@αβς.gr", listOf("αβσ.gr")) shouldBe false
    }

    test("a domain with a zero-width joiner does not pass for the domain without it") {
        isEmailDomainAllowed("user@con‍toso.com", listOf("contoso.com")) shouldBe false
    }

    test("a domain with ß matches in its xn-- spelling, which keeps it apart from the ASCII one") {
        isEmailDomainAllowed("user@xn--cla-pia.com", listOf("xn--cla-pia.com")) shouldBe true
        isEmailDomainAllowed("user@xn--cla-pia.com", listOf("class.com")) shouldBe false
    }
})

