package cz.pizavo.omnisign.domain.model.config.enums

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [EncryptionAlgorithm] compatibility matrix and properties.
 */
class EncryptionAlgorithmTest {
	
	@Test
	fun `RSA is compatible with all SHA-2 and SHA-3 hash algorithms`() {
		val expected = setOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512,
			HashAlgorithm.RIPEMD160
		)
		assertEquals(expected, EncryptionAlgorithm.RSA.compatibleHashAlgorithms)
	}
	
	@Test
	fun `RSA_SSA_PSS is compatible with SHA-2 SHA-3 and RIPEMD160`() {
		val compatible = EncryptionAlgorithm.RSA_SSA_PSS.compatibleHashAlgorithms
		assertTrue(HashAlgorithm.SHA256 in compatible)
		assertTrue(HashAlgorithm.SHA512 in compatible)
		assertTrue(HashAlgorithm.SHA3_512 in compatible)
		assertTrue(HashAlgorithm.RIPEMD160 in compatible)
		assertFalse(HashAlgorithm.WHIRLPOOL in compatible)
	}
	
	@Test
	fun `ECDSA is compatible with SHA-2 SHA-3 and RIPEMD160 but not WHIRLPOOL`() {
		val compatible = EncryptionAlgorithm.ECDSA.compatibleHashAlgorithms
		assertTrue(HashAlgorithm.SHA256 in compatible)
		assertTrue(HashAlgorithm.RIPEMD160 in compatible)
		assertFalse(HashAlgorithm.WHIRLPOOL in compatible)
	}
	
	@Test
	fun `PLAIN_ECDSA compatible set matches ECDSA`() {
		assertEquals(
			EncryptionAlgorithm.ECDSA.compatibleHashAlgorithms,
			EncryptionAlgorithm.PLAIN_ECDSA.compatibleHashAlgorithms
		)
	}
	
	@Test
	fun `DSA is not compatible with RIPEMD160 or WHIRLPOOL`() {
		val compatible = EncryptionAlgorithm.DSA.compatibleHashAlgorithms
		assertFalse(HashAlgorithm.RIPEMD160 in compatible)
		assertFalse(HashAlgorithm.WHIRLPOOL in compatible)
		assertTrue(HashAlgorithm.SHA256 in compatible)
		assertTrue(HashAlgorithm.SHA3_256 in compatible)
	}
	
	@Test
	fun `EDDSA has empty compatibleHashAlgorithms set`() {
		assertTrue(EncryptionAlgorithm.EDDSA.compatibleHashAlgorithms.isEmpty())
	}
	
	@Test
	fun `EDDSA hasFixedHashAlgorithm is true`() {
		assertTrue(EncryptionAlgorithm.EDDSA.hasFixedHashAlgorithm)
	}
	
	@Test
	fun `other algorithms do not have fixed hash algorithm`() {
		EncryptionAlgorithm.entries
			.filter { it != EncryptionAlgorithm.EDDSA }
			.forEach { alg ->
				assertFalse(alg.hasFixedHashAlgorithm, "$alg should not have a fixed hash algorithm")
			}
	}
	
	@Test
	fun `isCompatibleWith returns true for EDDSA regardless of hash algorithm`() {
		HashAlgorithm.entries.forEach { hash ->
			assertTrue(
				EncryptionAlgorithm.EDDSA.isCompatibleWith(hash),
				"EDDSA should be compatible with $hash"
			)
		}
	}
	
	@Test
	fun `isCompatibleWith returns false for RSA and WHIRLPOOL`() {
		assertFalse(EncryptionAlgorithm.RSA.isCompatibleWith(HashAlgorithm.WHIRLPOOL))
	}
	
	@Test
	fun `isCompatibleWith returns false for DSA and RIPEMD160`() {
		assertFalse(EncryptionAlgorithm.DSA.isCompatibleWith(HashAlgorithm.RIPEMD160))
	}
	
	@Test
	fun `isCompatibleWith returns true for RSA and SHA256`() {
		assertTrue(EncryptionAlgorithm.RSA.isCompatibleWith(HashAlgorithm.SHA256))
	}
	
	@Test
	fun `all non-EdDSA algorithms have non-empty compatibleHashAlgorithms`() {
		EncryptionAlgorithm.entries
			.filter { it != EncryptionAlgorithm.EDDSA }
			.forEach { alg ->
				assertTrue(
					alg.compatibleHashAlgorithms.isNotEmpty(),
					"$alg should have at least one compatible hash algorithm"
				)
			}
	}
	
	@Test
	fun `dssName is non-blank for all algorithms`() {
		EncryptionAlgorithm.entries.forEach { alg ->
			assertTrue(alg.dssName.isNotBlank(), "$alg dssName should not be blank")
		}
	}
	
	@Test
	fun `description is non-blank for all algorithms`() {
		EncryptionAlgorithm.entries.forEach { alg ->
			assertTrue(alg.description.isNotBlank(), "$alg description should not be blank")
		}
	}
}

