package cz.pizavo.omnisign.domain.model.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the disabled-algorithm enforcement in [ResolvedConfig.resolve].
 *
 * Verified properties:
 * - Globally disabled algorithms propagate to every higher-priority layer.
 * - Profile-disabled algorithms are enforced for operation overrides.
 * - Operation-only disabled algorithms are enforced within the same resolve call.
 * - Attempting to select a disabled algorithm as the resolved value produces an error.
 * - The resolved [ResolvedConfig.disabledHashAlgorithms] and
 *   [ResolvedConfig.disabledEncryptionAlgorithms] sets are the union of all layers.
 * - A completely unrelated algorithm selection is unaffected.
 */
class DisabledAlgorithmsTest {

	private fun global(
		defaultHash: HashAlgorithm = HashAlgorithm.SHA256,
		defaultEnc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = GlobalConfig(
		defaultHashAlgorithm = defaultHash,
		defaultEncryptionAlgorithm = defaultEnc,
		defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)

	private fun profile(
		hash: HashAlgorithm? = null,
		enc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = ProfileConfig(
		name = "test",
		hashAlgorithm = hash,
		encryptionAlgorithm = enc,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)

	private fun operation(
		hash: HashAlgorithm? = null,
		enc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = OperationConfig(
		hashAlgorithm = hash,
		encryptionAlgorithm = enc,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)

	private fun resolveOk(
		global: GlobalConfig,
		profile: ProfileConfig? = null,
		op: OperationConfig? = null
	) = ResolvedConfig.resolve(global, profile, op)
		.getOrElse { throw AssertionError("Expected success but got error: ${it.message}") }

	private fun resolveErr(
		global: GlobalConfig,
		profile: ProfileConfig? = null,
		op: OperationConfig? = null
	) = ResolvedConfig.resolve(global, profile, op).let { result ->
		result.fold(
			ifLeft = { it },
			ifRight = { throw AssertionError("Expected error but got resolved config") }
		)
	}

	// ── Happy-path: no disabled algorithms ──────────────────────────────────

	@Test
	fun `resolve with empty disabled sets succeeds and reports empty sets`() {
		val resolved = resolveOk(global())
		assertTrue(resolved.disabledHashAlgorithms.isEmpty())
		assertTrue(resolved.disabledEncryptionAlgorithms.isEmpty())
	}

	// ── Global disabled, no override ────────────────────────────────────────

	@Test
	fun `globally disabled hash algorithm is reflected in resolved set`() {
		val resolved = resolveOk(global(disabledHash = setOf(HashAlgorithm.RIPEMD160)))
		assertTrue(HashAlgorithm.RIPEMD160 in resolved.disabledHashAlgorithms)
	}

	@Test
	fun `globally disabled encryption algorithm is reflected in resolved set`() {
		val resolved = resolveOk(global(disabledEnc = setOf(EncryptionAlgorithm.DSA)))
		assertTrue(EncryptionAlgorithm.DSA in resolved.disabledEncryptionAlgorithms)
	}

	// ── Error: resolved hash is disabled ────────────────────────────────────

	@Test
	fun `error when global default hash is in global disabled set`() {
		val err = resolveErr(
			global(defaultHash = HashAlgorithm.SHA256, disabledHash = setOf(HashAlgorithm.SHA256))
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("SHA256"))
	}

	@Test
	fun `error when profile hash override is in global disabled set`() {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.SHA384)),
			profile = profile(hash = HashAlgorithm.SHA384)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("SHA384"))
	}

	@Test
	fun `error when operation hash override is in global disabled set`() {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.WHIRLPOOL)),
			op = operation(hash = HashAlgorithm.WHIRLPOOL)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("WHIRLPOOL"))
	}

	@Test
	fun `error when operation hash override is in profile disabled set`() {
		val err = resolveErr(
			global = global(),
			profile = profile(disabledHash = setOf(HashAlgorithm.SHA512)),
			op = operation(hash = HashAlgorithm.SHA512)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("SHA512"))
	}

	// ── Error: resolved encryption is disabled ───────────────────────────────

	@Test
	fun `error when global default encryption is in global disabled set`() {
		val err = resolveErr(
			global(defaultEnc = EncryptionAlgorithm.RSA, disabledEnc = setOf(EncryptionAlgorithm.RSA))
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("RSA"))
	}

	@Test
	fun `error when profile encryption override is in global disabled set`() {
		val err = resolveErr(
			global = global(disabledEnc = setOf(EncryptionAlgorithm.DSA)),
			profile = profile(enc = EncryptionAlgorithm.DSA)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("DSA"))
	}

	@Test
	fun `error when operation encryption override is in profile disabled set`() {
		val err = resolveErr(
			global = global(),
			profile = profile(disabledEnc = setOf(EncryptionAlgorithm.ECDSA)),
			op = operation(enc = EncryptionAlgorithm.ECDSA)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
		assertTrue(err.message.contains("ECDSA"))
	}

	// ── Union across layers ──────────────────────────────────────────────────

	@Test
	fun `disabled sets from all layers are unioned in resolved config`() {
		val resolved = resolveOk(
			global = global(disabledHash = setOf(HashAlgorithm.RIPEMD160)),
			profile = profile(disabledHash = setOf(HashAlgorithm.WHIRLPOOL)),
			op = operation(disabledHash = setOf(HashAlgorithm.SHA3_512))
		)
		assertEquals(
			setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL, HashAlgorithm.SHA3_512),
			resolved.disabledHashAlgorithms
		)
	}

	@Test
	fun `resolved hash that is not in any disabled set succeeds`() {
		val resolved = resolveOk(
			global = global(
				defaultHash = HashAlgorithm.SHA256,
				disabledHash = setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL)
			)
		)
		assertEquals(HashAlgorithm.SHA256, resolved.hashAlgorithm)
	}

	// ── Lower-priority layer cannot re-enable ───────────────────────────────

	@Test
	fun `operation cannot re-enable globally disabled algorithm by not listing it`() {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.SHA384)),
			op = operation(hash = HashAlgorithm.SHA384)
		)
		assertIs<ConfigurationError.InvalidConfiguration>(err)
	}

	// ── null encryption algorithm is never blocked ───────────────────────────

	@Test
	fun `null encryption algorithm is never rejected even if set is non-empty`() {
		val resolved = resolveOk(
			global = global(
				defaultEnc = null,
				disabledEnc = setOf(EncryptionAlgorithm.RSA, EncryptionAlgorithm.DSA)
			)
		)
		assertTrue(resolved.encryptionAlgorithm == null)
	}

	// ── Profile-disabled not in global, but present on profile ───────────────

	@Test
	fun `profile can additionally disable algorithms not disabled globally`() {
		val resolved = resolveOk(
			global = global(),
			profile = profile(disabledHash = setOf(HashAlgorithm.SHA3_256))
		)
		assertTrue(HashAlgorithm.SHA3_256 in resolved.disabledHashAlgorithms)
	}
}

