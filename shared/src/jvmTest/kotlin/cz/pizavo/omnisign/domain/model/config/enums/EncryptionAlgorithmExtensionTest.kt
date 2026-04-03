package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm as DssEncryptionAlgorithm

/**
 * Verifies exhaustive mapping of domain [EncryptionAlgorithm] to DSS [DssEncryptionAlgorithm].
 */
class EncryptionAlgorithmExtensionTest : FunSpec({

	test("RSA maps to DSS RSA") {
		EncryptionAlgorithm.RSA.toDss() shouldBe DssEncryptionAlgorithm.RSA
	}

	test("RSA_SSA_PSS maps to DSS RSASSA-PSS") {
		EncryptionAlgorithm.RSA_SSA_PSS.toDss().name shouldBe "RSASSA_PSS"
	}

	test("ECDSA maps to DSS ECDSA") {
		EncryptionAlgorithm.ECDSA.toDss() shouldBe DssEncryptionAlgorithm.ECDSA
	}

	test("PLAIN_ECDSA maps to DSS PLAIN-ECDSA") {
		EncryptionAlgorithm.PLAIN_ECDSA.toDss().name shouldBe "PLAIN_ECDSA"
	}

	test("DSA maps to DSS DSA") {
		EncryptionAlgorithm.DSA.toDss() shouldBe DssEncryptionAlgorithm.DSA
	}

	test("EDDSA maps to DSS EdDSA") {
		EncryptionAlgorithm.EDDSA.toDss().name shouldBe "EDDSA"
	}

	test("all domain entries resolve via forName without exception") {
		EncryptionAlgorithm.entries.forEach { enc ->
			val dss = enc.toDss()
			dss shouldBe DssEncryptionAlgorithm.forName(enc.dssName)
		}
	}
})
