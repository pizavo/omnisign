package cz.pizavo.omnisign

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Placeholder test verifying the Kotest framework is wired correctly in composeApp.
 */
class ComposeAppCommonTest : FunSpec({

	test("basic arithmetic sanity check") {
		(1 + 2) shouldBe 3
	}
})
