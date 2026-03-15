package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [SignatureLevel] entries and their ordering.
 */
class SignatureLevelTest : FunSpec({

	test("entries count is four PAdES levels") {
		SignatureLevel.entries.size shouldBe 4
	}

	test("entries are ordered B then T then LT then LTA") {
		SignatureLevel.entries.map { it.name } shouldBe listOf(
			"PADES_BASELINE_B",
			"PADES_BASELINE_T",
			"PADES_BASELINE_LT",
			"PADES_BASELINE_LTA"
		)
	}

	test("ordinal reflects increasing level of assurance") {
		val b = SignatureLevel.PADES_BASELINE_B.ordinal
		val t = SignatureLevel.PADES_BASELINE_T.ordinal
		val lt = SignatureLevel.PADES_BASELINE_LT.ordinal
		val lta = SignatureLevel.PADES_BASELINE_LTA.ordinal

		(b < t) shouldBe true
		(t < lt) shouldBe true
		(lt < lta) shouldBe true
	}
})

