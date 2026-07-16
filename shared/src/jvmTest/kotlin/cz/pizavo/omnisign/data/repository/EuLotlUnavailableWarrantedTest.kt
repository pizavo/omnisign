package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the gate for the "EU LOTL unavailable" validation warning: it fires only when the EU LOTL
 * is enabled, failed to load, and actually left a signature/timestamp INDETERMINATE with a
 * no-trusted-chain sub-indication — so a report the LOTL failure did not affect (everything passed,
 * the LOTL loaded fine, or the INDETERMINATE is for an unrelated reason such as revocation) stays
 * quiet. The sub-indication strings mirror DSS's `SubIndication` enum names, which is what the report
 * stores.
 */
class EuLotlUnavailableWarrantedTest : FunSpec({

	val noChain = ValidationIndication.INDETERMINATE to "NO_CERTIFICATE_CHAIN_FOUND"

	test("warns when the EU LOTL failed to load and a signature has no trusted chain") {
		euLotlUnavailableWarranted(
			results = listOf(noChain),
			useEuLotl = true,
			euLotlTrustLoaded = false,
		) shouldBe true
	}

	test("warns for the past-validation no-trusted-chain variant") {
		euLotlUnavailableWarranted(
			results = listOf(ValidationIndication.INDETERMINATE to "NO_CERTIFICATE_CHAIN_FOUND_NO_POE"),
			useEuLotl = true,
			euLotlTrustLoaded = false,
		) shouldBe true
	}

	test("no warning when the EU LOTL loaded, even with an untrusted chain") {
		euLotlUnavailableWarranted(
			results = listOf(noChain),
			useEuLotl = true,
			euLotlTrustLoaded = true,
		) shouldBe false
	}

	test("no warning for an INDETERMINATE that is not a trusted-chain failure") {
		euLotlUnavailableWarranted(
			results = listOf(ValidationIndication.INDETERMINATE to "TRY_LATER"),
			useEuLotl = true,
			euLotlTrustLoaded = false,
		) shouldBe false
	}

	test("no warning when everything passed despite the EU LOTL failing to load") {
		euLotlUnavailableWarranted(
			results = listOf(ValidationIndication.TOTAL_PASSED to null),
			useEuLotl = true,
			euLotlTrustLoaded = false,
		) shouldBe false
	}

	test("no warning when the configuration does not use the EU LOTL") {
		euLotlUnavailableWarranted(
			results = listOf(noChain),
			useEuLotl = false,
			euLotlTrustLoaded = false,
		) shouldBe false
	}

	test("no warning for a failed signature") {
		euLotlUnavailableWarranted(
			results = listOf(ValidationIndication.TOTAL_FAILED to "SIG_CRYPTO_FAILURE"),
			useEuLotl = true,
			euLotlTrustLoaded = false,
		) shouldBe false
	}
})
