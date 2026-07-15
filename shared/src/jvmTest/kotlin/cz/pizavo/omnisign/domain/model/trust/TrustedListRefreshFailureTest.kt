package cz.pizavo.omnisign.domain.model.trust

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock

/**
 * Verifies how [TrustedListRefreshFailure.of] collapses a refresh session's failures into
 * one aggregate: the EU LOTL is prioritised over any custom-list failures, and the
 * custom-only cases split by count into [TrustedListRefreshFailure.CustomList] and
 * [TrustedListRefreshFailure.Multiple].
 */
class TrustedListRefreshFailureTest : FunSpec({

	val at = Clock.System.now()

	test("no failures resolve to null") {
		TrustedListRefreshFailure.of(lotlFailed = false, failedCustomNames = emptyList(), at = at) shouldBe null
	}

	test("an EU LOTL failure alone resolves to EuLotl") {
		TrustedListRefreshFailure.of(lotlFailed = true, failedCustomNames = emptyList(), at = at) shouldBe
			TrustedListRefreshFailure.EuLotl(at)
	}

	test("the EU LOTL failing alongside custom lists resolves to EuLotlAndOthers") {
		TrustedListRefreshFailure.of(lotlFailed = true, failedCustomNames = listOf("A", "B"), at = at) shouldBe
			TrustedListRefreshFailure.EuLotlAndOthers(at)
	}

	test("the EU LOTL failing with a single custom list still resolves to EuLotlAndOthers") {
		TrustedListRefreshFailure.of(lotlFailed = true, failedCustomNames = listOf("A"), at = at) shouldBe
			TrustedListRefreshFailure.EuLotlAndOthers(at)
	}

	test("a single custom failure names the list") {
		TrustedListRefreshFailure.of(lotlFailed = false, failedCustomNames = listOf("My List"), at = at) shouldBe
			TrustedListRefreshFailure.CustomList("My List", at)
	}

	test("multiple custom failures without the LOTL resolve to Multiple") {
		TrustedListRefreshFailure.of(lotlFailed = false, failedCustomNames = listOf("A", "B"), at = at) shouldBe
			TrustedListRefreshFailure.Multiple(at)
	}
})
