package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Verifies [AlgorithmConstraintLevel] entries, their descriptions, and DSS-level mapping.
 */
class AlgorithmConstraintLevelTest : FunSpec({
	
	test("all entries have non-blank descriptions") {
		AlgorithmConstraintLevel.entries.forEach { level ->
			level.description.shouldNotBeBlank()
		}
	}
	
	test("entries cover all four DSS Level counterparts") {
		AlgorithmConstraintLevel.entries.shouldHaveSize(4)
		AlgorithmConstraintLevel.entries.map { it.name } shouldBe
			listOf("FAIL", "WARN", "INFORM", "IGNORE")
	}
})

