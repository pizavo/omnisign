package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import eu.europa.esig.dss.enumerations.SignatureLevel as DssSignatureLevel

/**
 * Verifies exhaustive mapping of domain [SignatureLevel] to DSS [DssSignatureLevel].
 */
class SignatureLevelExtensionTest : FunSpec({

	test("PADES_BASELINE_B maps to PAdES_BASELINE_B") {
		SignatureLevel.PADES_BASELINE_B.toDss() shouldBe DssSignatureLevel.PAdES_BASELINE_B
	}

	test("PADES_BASELINE_T maps to PAdES_BASELINE_T") {
		SignatureLevel.PADES_BASELINE_T.toDss() shouldBe DssSignatureLevel.PAdES_BASELINE_T
	}

	test("PADES_BASELINE_LT maps to PAdES_BASELINE_LT") {
		SignatureLevel.PADES_BASELINE_LT.toDss() shouldBe DssSignatureLevel.PAdES_BASELINE_LT
	}

	test("PADES_BASELINE_LTA maps to PAdES_BASELINE_LTA") {
		SignatureLevel.PADES_BASELINE_LTA.toDss() shouldBe DssSignatureLevel.PAdES_BASELINE_LTA
	}

	test("all domain entries have a DSS mapping") {
		SignatureLevel.entries.forEach { level ->
			val dss = level.toDss()
			dss.name shouldBe level.name.replace("PADES", "PAdES")
		}
	}
})

