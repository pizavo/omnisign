package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.datetime.LocalDate

/**
 * Verifies [HashAlgorithm] enum metadata: DSS names, descriptions, notes, and ETSI expiration dates.
 */
class HashAlgorithmTest : FunSpec({

	test("dssName matches expected values for SHA-2 family") {
		HashAlgorithm.SHA256.dssName shouldBe "SHA256"
		HashAlgorithm.SHA384.dssName shouldBe "SHA384"
		HashAlgorithm.SHA512.dssName shouldBe "SHA512"
	}

	test("dssName matches expected values for SHA-3 family") {
		HashAlgorithm.SHA3_256.dssName shouldBe "SHA3-256"
		HashAlgorithm.SHA3_384.dssName shouldBe "SHA3-384"
		HashAlgorithm.SHA3_512.dssName shouldBe "SHA3-512"
	}

	test("dssName matches expected values for legacy algorithms") {
		HashAlgorithm.WHIRLPOOL.dssName shouldBe "WHIRLPOOL"
		HashAlgorithm.RIPEMD160.dssName shouldBe "RIPEMD160"
	}

	test("description is non-blank for all algorithms") {
		HashAlgorithm.entries.forEach { alg -> alg.description.shouldNotBeBlank() }
	}

	test("SHA-2 and SHA-3 families have no expiration date") {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		).forEach { alg -> alg.expirationDate.shouldBeNull() }
	}

	test("RIPEMD160 has ETSI expiration date 2014-08-01") {
		HashAlgorithm.RIPEMD160.expirationDate shouldBe LocalDate(2014, 8, 1)
	}

	test("WHIRLPOOL has ETSI expiration date 2020-12-01") {
		HashAlgorithm.WHIRLPOOL.expirationDate shouldBe LocalDate(2020, 12, 1)
	}

	test("RIPEMD160 and WHIRLPOOL have non-null notes") {
		HashAlgorithm.RIPEMD160.notes.shouldNotBeNull()
		HashAlgorithm.WHIRLPOOL.notes.shouldNotBeNull()
	}

	test("SHA-2 family has null notes") {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
		).forEach { alg -> alg.notes.shouldBeNull() }
	}

	test("SHA-3 family has non-null notes about Windows CNG incompatibility") {
		listOf(
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		).forEach { alg -> alg.notes.shouldNotBeNull() }
	}

	test("entries count matches expected number of algorithms") {
		HashAlgorithm.entries.size shouldBe 8
	}
})

