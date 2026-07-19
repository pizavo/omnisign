package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [isRedirectUriAllowed].
 *
 * The filter's whole value is that it does *not* generalise, so most of these tests pin the
 * near-misses an operator might assume are covered — a trailing slash, a sub-path, a
 * look-alike host — and assert they are refused.
 */
class RedirectUriFilterTest : FunSpec({

    val allow = listOf("https://omnisign.example.com/", "https://omnisign.example.com/app")

    test("accepts an entry that appears verbatim in the allowlist") {
        isRedirectUriAllowed("https://omnisign.example.com/", allow) shouldBe true
        isRedirectUriAllowed("https://omnisign.example.com/app", allow) shouldBe true
    }

    test("rejects everything when the allowlist is empty") {
        isRedirectUriAllowed("https://omnisign.example.com/", emptyList()) shouldBe false
    }

    test("rejects a look-alike host that merely starts the same way") {
        isRedirectUriAllowed("https://omnisign.example.com.evil.test/", allow) shouldBe false
    }

    test("rejects a different path on an allowed host (no host-only matching)") {
        isRedirectUriAllowed("https://omnisign.example.com/elsewhere", allow) shouldBe false
    }

    test("rejects a trailing-slash mismatch (no normalisation)") {
        isRedirectUriAllowed("https://omnisign.example.com", allow) shouldBe false
        isRedirectUriAllowed("https://omnisign.example.com/app/", allow) shouldBe false
    }

    test("rejects a scheme downgrade") {
        isRedirectUriAllowed("http://omnisign.example.com/", allow) shouldBe false
    }

    test("rejects an appended query or fragment") {
        isRedirectUriAllowed("https://omnisign.example.com/?next=/admin", allow) shouldBe false
        isRedirectUriAllowed("https://omnisign.example.com/#/admin", allow) shouldBe false
    }
})
