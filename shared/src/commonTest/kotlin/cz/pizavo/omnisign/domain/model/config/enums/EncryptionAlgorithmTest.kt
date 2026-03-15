package cz.pizavo.omnisign.domain.model.config.enums

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Verifies [EncryptionAlgorithm] compatibility maps, fixed-hash semantics,
 * and metadata properties.
 */
class EncryptionAlgorithmTest : FunSpec({
	
	test("RSA is compatible with all SHA-2 and SHA-3 hash algorithms") {
		EncryptionAlgorithm.RSA.compatibleHashAlgorithms shouldBe setOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512,
			HashAlgorithm.RIPEMD160
		)
	}
	
	test("RSA_SSA_PSS is compatible with SHA-2 SHA-3 and RIPEMD160") {
		val compatible = EncryptionAlgorithm.RSA_SSA_PSS.compatibleHashAlgorithms
		(HashAlgorithm.SHA256 in compatible).shouldBeTrue()
		(HashAlgorithm.SHA512 in compatible).shouldBeTrue()
		(HashAlgorithm.SHA3_512 in compatible).shouldBeTrue()
		(HashAlgorithm.RIPEMD160 in compatible).shouldBeTrue()
		(HashAlgorithm.WHIRLPOOL in compatible).shouldBeFalse()
	}
	
	test("ECDSA is compatible with SHA-2 SHA-3 and RIPEMD160 but not WHIRLPOOL") {
		val compatible = EncryptionAlgorithm.ECDSA.compatibleHashAlgorithms
		(HashAlgorithm.SHA256 in compatible).shouldBeTrue()
		(HashAlgorithm.RIPEMD160 in compatible).shouldBeTrue()
		(HashAlgorithm.WHIRLPOOL in compatible).shouldBeFalse()
	}
	
	test("PLAIN_ECDSA compatible set matches ECDSA") {
		EncryptionAlgorithm.PLAIN_ECDSA.compatibleHashAlgorithms shouldBe
			EncryptionAlgorithm.ECDSA.compatibleHashAlgorithms
	}
	
	test("DSA is not compatible with RIPEMD160 or WHIRLPOOL") {
		val compatible = EncryptionAlgorithm.DSA.compatibleHashAlgorithms
		(HashAlgorithm.RIPEMD160 in compatible).shouldBeFalse()
		(HashAlgorithm.WHIRLPOOL in compatible).shouldBeFalse()
		(HashAlgorithm.SHA256 in compatible).shouldBeTrue()
		(HashAlgorithm.SHA3_256 in compatible).shouldBeTrue()
	}
	
	test("EDDSA has empty compatibleHashAlgorithms set") {
		EncryptionAlgorithm.EDDSA.compatibleHashAlgorithms.shouldBeEmpty()
	}
	
	test("EDDSA hasFixedHashAlgorithm is true") {
		EncryptionAlgorithm.EDDSA.hasFixedHashAlgorithm.shouldBeTrue()
	}
	
	test("other algorithms do not have fixed hash algorithm") {
		EncryptionAlgorithm.entries
			.filter { it != EncryptionAlgorithm.EDDSA }
			.forEach { alg -> alg.hasFixedHashAlgorithm.shouldBeFalse() }
	}
	
	test("isCompatibleWith returns true for EDDSA regardless of hash algorithm") {
		HashAlgorithm.entries.forEach { hash ->
			EncryptionAlgorithm.EDDSA.isCompatibleWith(hash).shouldBeTrue()
		}
	}
	
	test("isCompatibleWith returns false for RSA and WHIRLPOOL") {
		EncryptionAlgorithm.RSA.isCompatibleWith(HashAlgorithm.WHIRLPOOL).shouldBeFalse()
	}
	
	test("isCompatibleWith returns false for DSA and RIPEMD160") {
		EncryptionAlgorithm.DSA.isCompatibleWith(HashAlgorithm.RIPEMD160).shouldBeFalse()
	}
	
	test("isCompatibleWith returns true for RSA and SHA256") {
		EncryptionAlgorithm.RSA.isCompatibleWith(HashAlgorithm.SHA256).shouldBeTrue()
	}
	
	test("all non-EdDSA algorithms have non-empty compatibleHashAlgorithms") {
		EncryptionAlgorithm.entries
			.filter { it != EncryptionAlgorithm.EDDSA }
			.forEach { alg -> alg.compatibleHashAlgorithms.shouldNotBeEmpty() }
	}
	
	test("dssName is non-blank for all algorithms") {
		EncryptionAlgorithm.entries.forEach { alg -> alg.dssName.shouldNotBeBlank() }
	}
	
	test("description is non-blank for all algorithms") {
		EncryptionAlgorithm.entries.forEach { alg -> alg.description.shouldNotBeBlank() }
	}
})

