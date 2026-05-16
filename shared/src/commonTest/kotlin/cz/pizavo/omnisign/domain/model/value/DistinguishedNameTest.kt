package cz.pizavo.omnisign.domain.model.value

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [commonNameOf] — the CN-from-DN extraction shared by certificate display and
 * the signing-critical alias derivation.  The empty-string vs `null` distinction is part
 * of the contract both callers rely on for behaviour-preserving fallbacks.
 */
class DistinguishedNameTest : FunSpec({

    test("extracts the CN value from a typical RFC 2253 DN") {
        commonNameOf("CN=Jane Doe,O=Acme,C=CZ") shouldBe "Jane Doe"
    }

    test("trims whitespace around the CN value and its RDN") {
        commonNameOf("O=Acme, CN= Jane Doe ,C=CZ") shouldBe "Jane Doe"
    }

    test("finds the CN even when it is not the first RDN") {
        commonNameOf("O=Acme,CN=Bob,C=CZ") shouldBe "Bob"
    }

    test("returns null when no CN RDN is present") {
        commonNameOf("O=Acme,C=CZ") shouldBe null
    }

    test("returns empty string for a present but empty CN") {
        commonNameOf("CN=,O=Acme") shouldBe ""
    }

    test("handles a DN consisting solely of the CN") {
        commonNameOf("CN=Solo") shouldBe "Solo"
    }

    test("ignores OID-form RDNs and returns the CN unchanged") {
        commonNameOf("CN=Píža Vojtěch,2.5.4.97=#160b56") shouldBe "Píža Vojtěch"
    }
})
