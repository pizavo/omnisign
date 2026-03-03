package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import arrow.core.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ResolvedConfig.resolve], specifically the [AlgorithmConstraintsConfig]
 * merging logic added to [ResolvedConfig.mergeValidationConfig].
 */
class ResolvedConfigTest {
	
	private fun globalConfig(constraints: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig()) =
		GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			validation = ValidationConfig(algorithmConstraints = constraints)
		)
	
	private fun profileConfig(constraints: AlgorithmConstraintsConfig?) =
		ProfileConfig(
			name = "test",
			validation = constraints?.let { ValidationConfig(algorithmConstraints = it) }
		)
	
	private fun resolve(
		global: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig(),
		profile: AlgorithmConstraintsConfig? = null,
		operation: AlgorithmConstraintsConfig? = null
	) = ResolvedConfig.resolve(
		global = globalConfig(global),
		profile = profile?.let { profileConfig(it) },
		operationOverrides = operation?.let {
			OperationConfig(validation = ValidationConfig(algorithmConstraints = it))
		}
	).getOrElse { error -> throw AssertionError("Unexpected resolution failure: ${error.message}") }
		.validation.algorithmConstraints
	
	// ── Terminal fallback to DEFAULT ──────────────────────────────────────────
	
	@Test
	fun `all-null global resolves to DEFAULT severity values`() {
		val result = resolve(global = AlgorithmConstraintsConfig())
		assertEquals(AlgorithmConstraintsConfig.DEFAULT.expirationLevel, result.expirationLevel)
		assertEquals(AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate, result.expirationLevelAfterUpdate)
	}
	
	@Test
	fun `global with explicit values propagates them`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.WARN,
				expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
			)
		)
		assertEquals(AlgorithmConstraintLevel.WARN, result.expirationLevel)
		assertEquals(AlgorithmConstraintLevel.INFORM, result.expirationLevelAfterUpdate)
	}
	
	// ── Profile layer ─────────────────────────────────────────────────────────
	
	@Test
	fun `profile null fields inherit from global`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			profile = AlgorithmConstraintsConfig()
		)
		assertEquals(AlgorithmConstraintLevel.WARN, result.expirationLevel)
	}
	
	@Test
	fun `profile explicit field overrides global`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.FAIL),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN)
		)
		assertEquals(AlgorithmConstraintLevel.WARN, result.expirationLevel)
	}
	
	@Test
	fun `profile overrides only expirationLevel, global expirationLevelAfterUpdate still used`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.FAIL,
				expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
			),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN)
		)
		assertEquals(AlgorithmConstraintLevel.WARN, result.expirationLevel)
		assertEquals(AlgorithmConstraintLevel.INFORM, result.expirationLevelAfterUpdate)
	}
	
	// ── Operation layer ───────────────────────────────────────────────────────
	
	@Test
	fun `operation explicit field overrides profile and global`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.FAIL),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			operation = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.IGNORE)
		)
		assertEquals(AlgorithmConstraintLevel.IGNORE, result.expirationLevel)
	}
	
	@Test
	fun `operation null fields fall through to profile then global`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			operation = AlgorithmConstraintsConfig()
		)
		assertEquals(AlgorithmConstraintLevel.WARN, result.expirationLevel)
		assertEquals(AlgorithmConstraintLevel.INFORM, result.expirationLevelAfterUpdate)
	}
	
	// ── policyUpdateDate ──────────────────────────────────────────────────────
	
	@Test
	fun `policyUpdateDate null in all layers produces null in resolved`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(),
			profile = AlgorithmConstraintsConfig(),
			operation = AlgorithmConstraintsConfig()
		)
		assertNull(result.policyUpdateDate)
	}
	
	@Test
	fun `policyUpdateDate operation wins over profile and global`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(policyUpdateDate = "2024-01-01"),
			profile = AlgorithmConstraintsConfig(policyUpdateDate = "2025-01-01"),
			operation = AlgorithmConstraintsConfig(policyUpdateDate = "2026-01-01")
		)
		assertEquals("2026-01-01", result.policyUpdateDate)
	}
	
	@Test
	fun `policyUpdateDate falls through to global when operation and profile are null`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(policyUpdateDate = "2024-01-01"),
			profile = AlgorithmConstraintsConfig(),
			operation = AlgorithmConstraintsConfig()
		)
		assertEquals("2024-01-01", result.policyUpdateDate)
	}
	
	// ── expirationDateOverrides (additive merge) ──────────────────────────────
	
	@Test
	fun `overrides from all layers are merged additively`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("WHIRLPOOL" to "2031-01-01")),
			operation = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("SHA256" to "2032-01-01"))
		)
		assertEquals("2030-01-01", result.expirationDateOverrides["RIPEMD160"])
		assertEquals("2031-01-01", result.expirationDateOverrides["WHIRLPOOL"])
		assertEquals("2032-01-01", result.expirationDateOverrides["SHA256"])
	}
	
	@Test
	fun `higher-priority layer wins on key collision in expirationDateOverrides`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2035-01-01")),
			operation = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01"))
		)
		assertEquals("2040-01-01", result.expirationDateOverrides["RIPEMD160"])
	}
	
	@Test
	fun `global overrides are present when profile has none`() {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig()
		)
		assertEquals("2030-01-01", result.expirationDateOverrides["RIPEMD160"])
	}
}

