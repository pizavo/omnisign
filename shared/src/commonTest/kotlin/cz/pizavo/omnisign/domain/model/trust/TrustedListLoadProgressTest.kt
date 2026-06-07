package cz.pizavo.omnisign.domain.model.trust

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies the [TrustedListLoadProgress.fraction] indeterminate/determinate semantics.
 */
class TrustedListLoadProgressTest : FunSpec({

	test("fraction is null (indeterminate) while the member-state count is unknown") {
		TrustedListLoadProgress().fraction.shouldBeNull()
		TrustedListLoadProgress(loaded = 0, total = 0).fraction.shouldBeNull()
	}

	test("fraction is loaded over total once the lists are known") {
		TrustedListLoadProgress(loaded = 3, total = 12).fraction shouldBe 0.25f
		TrustedListLoadProgress(loaded = 0, total = 31).fraction shouldBe 0f
		TrustedListLoadProgress(loaded = 31, total = 31).fraction shouldBe 1f
	}

	test("fraction is coerced into 0f..1f") {
		TrustedListLoadProgress(loaded = 5, total = 4).fraction shouldBe 1f
	}
})
