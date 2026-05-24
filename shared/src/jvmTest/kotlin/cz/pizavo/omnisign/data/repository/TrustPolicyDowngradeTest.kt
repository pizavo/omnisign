package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the per-reference trust-policy downgrade decision: only role-mismatched store-managed
 * anchors trigger a downgrade, while an empty managed set (trusted-list / unmanaged trust) never
 * does, and [TrustedCertificateType.ANY] satisfies both roles.
 */
class TrustPolicyDowngradeTest : FunSpec({

	val ca = TrustedCertificateType.CA
	val tsa = TrustedCertificateType.TSA
	val any = TrustedCertificateType.ANY

	test("an empty managed set is never downgraded") {
		isDowngradedByPolicy(emptyList(), ca) shouldBe false
		isDowngradedByPolicy(emptyList(), tsa) shouldBe false
	}

	test("a signature is downgraded when its only managed anchor is TSA-only") {
		isDowngradedByPolicy(listOf(tsa), ca) shouldBe true
	}

	test("a signature is kept when a managed anchor grants CA or ANY") {
		isDowngradedByPolicy(listOf(ca), ca) shouldBe false
		isDowngradedByPolicy(listOf(any), ca) shouldBe false
		isDowngradedByPolicy(listOf(tsa, ca), ca) shouldBe false
	}

	test("a timestamp is downgraded when its only managed anchor is CA-only") {
		isDowngradedByPolicy(listOf(ca), tsa) shouldBe true
	}

	test("a timestamp is kept when a managed anchor grants TSA or ANY") {
		isDowngradedByPolicy(listOf(tsa), tsa) shouldBe false
		isDowngradedByPolicy(listOf(any), tsa) shouldBe false
	}
})
