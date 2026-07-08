package cz.pizavo.omnisign.ui.branding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [brandedTitle] and [organizationChainLabel], pinning the enforced-attribution
 * composition: the OmniSign product name is always the final title segment, provider labels form a
 * most-local-first chain (frontend deployer, then server operator), blanks are dropped, and a label
 * shared by both parties (the common single-provider case) is de-duplicated.
 */
class BrandingTest : FunSpec({

    test("no organization names yield the plain product name") {
        brandedTitle(null, null) shouldBe "OmniSign"
    }

    test("blank organization names yield the plain product name") {
        brandedTitle("   ", "  ") shouldBe "OmniSign"
    }

    test("a frontend-deployer label alone is prefixed before OmniSign") {
        brandedTitle("University of Ostrava", null) shouldBe "University of Ostrava · OmniSign"
    }

    test("a server-operator label alone is prefixed before OmniSign") {
        brandedTitle(null, "Microsoft") shouldBe "Microsoft · OmniSign"
    }

    test("distinct deployer and operator labels form a most-local-first chain") {
        brandedTitle("University of Ostrava", "Microsoft") shouldBe "University of Ostrava · Microsoft · OmniSign"
    }

    test("an operator label equal to the deployer label is de-duplicated") {
        brandedTitle("University of Ostrava", "University of Ostrava") shouldBe "University of Ostrava · OmniSign"
    }

    test("organizationChainLabel is null when no provider branding is set") {
        organizationChainLabel(null, "  ") shouldBe null
    }

    test("organizationChainLabel joins the de-duplicated chain without the product name") {
        organizationChainLabel("University of Ostrava", "Microsoft") shouldBe "University of Ostrava · Microsoft"
    }
})
