package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import eu.europa.esig.dss.enumerations.Level as DssLevel

/**
 * Verifies exhaustive mapping of domain [AlgorithmConstraintLevel] to DSS [DssLevel].
 */
class AlgorithmConstraintLevelExtensionTest : FunSpec({

	test("FAIL maps to DSS FAIL") {
		AlgorithmConstraintLevel.FAIL.toDss() shouldBe DssLevel.FAIL
	}

	test("WARN maps to DSS WARN") {
		AlgorithmConstraintLevel.WARN.toDss() shouldBe DssLevel.WARN
	}

	test("INFORM maps to DSS INFORM") {
		AlgorithmConstraintLevel.INFORM.toDss() shouldBe DssLevel.INFORM
	}

	test("IGNORE maps to DSS IGNORE") {
		AlgorithmConstraintLevel.IGNORE.toDss() shouldBe DssLevel.IGNORE
	}

	test("all domain entries map to a DSS level with the same name") {
		AlgorithmConstraintLevel.entries.forEach { level ->
			level.toDss().name shouldBe level.name
		}
	}
})

