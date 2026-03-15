package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies [AlgorithmConstraintsConfig] merging across global, profile, and operation layers.
 */
class ResolvedConfigTest : FunSpec({
	
	fun globalConfig(constraints: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig()) =
		GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			validation = ValidationConfig(algorithmConstraints = constraints)
		)
	
	fun profileConfig(constraints: AlgorithmConstraintsConfig?) =
		ProfileConfig(
			name = "test",
			validation = constraints?.let { ValidationConfig(algorithmConstraints = it) }
		)
	
	fun resolve(
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
	
	test("all-null global resolves to DEFAULT severity values") {
		val result = resolve(global = AlgorithmConstraintsConfig())
		result.expirationLevel shouldBe AlgorithmConstraintsConfig.DEFAULT.expirationLevel
		result.expirationLevelAfterUpdate shouldBe AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate
	}
	
	test("global with explicit values propagates them") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.WARN,
				expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
			)
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
		result.expirationLevelAfterUpdate shouldBe AlgorithmConstraintLevel.INFORM
	}
	
	test("profile null fields inherit from global") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			profile = AlgorithmConstraintsConfig()
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
	}
	
	test("profile explicit field overrides global") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.FAIL),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN)
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
	}
	
	test("profile overrides only expirationLevel, global expirationLevelAfterUpdate still used") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.FAIL,
				expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
			),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN)
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
		result.expirationLevelAfterUpdate shouldBe AlgorithmConstraintLevel.INFORM
	}
	
	test("operation explicit field overrides profile and global") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.FAIL),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			operation = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.IGNORE)
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.IGNORE
	}
	
	test("operation null fields fall through to profile then global") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM),
			profile = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			operation = AlgorithmConstraintsConfig()
		)
		result.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
		result.expirationLevelAfterUpdate shouldBe AlgorithmConstraintLevel.INFORM
	}
	
	test("policyUpdateDate null in all layers produces null in resolved") {
		resolve(
			global = AlgorithmConstraintsConfig(),
			profile = AlgorithmConstraintsConfig(),
			operation = AlgorithmConstraintsConfig()
		).policyUpdateDate.shouldBeNull()
	}
	
	test("policyUpdateDate operation wins over profile and global") {
		resolve(
			global = AlgorithmConstraintsConfig(policyUpdateDate = "2024-01-01"),
			profile = AlgorithmConstraintsConfig(policyUpdateDate = "2025-01-01"),
			operation = AlgorithmConstraintsConfig(policyUpdateDate = "2026-01-01")
		).policyUpdateDate shouldBe "2026-01-01"
	}
	
	test("policyUpdateDate falls through to global when operation and profile are null") {
		resolve(
			global = AlgorithmConstraintsConfig(policyUpdateDate = "2024-01-01"),
			profile = AlgorithmConstraintsConfig(),
			operation = AlgorithmConstraintsConfig()
		).policyUpdateDate shouldBe "2024-01-01"
	}
	
	test("overrides from all layers are merged additively") {
		val result = resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("WHIRLPOOL" to "2031-01-01")),
			operation = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("SHA256" to "2032-01-01"))
		)
		result.expirationDateOverrides["RIPEMD160"] shouldBe "2030-01-01"
		result.expirationDateOverrides["WHIRLPOOL"] shouldBe "2031-01-01"
		result.expirationDateOverrides["SHA256"] shouldBe "2032-01-01"
	}
	
	test("higher-priority layer wins on key collision in expirationDateOverrides") {
		resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2035-01-01")),
			operation = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01"))
		).expirationDateOverrides["RIPEMD160"] shouldBe "2040-01-01"
	}
	
	test("global overrides are present when profile has none") {
		resolve(
			global = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01")),
			profile = AlgorithmConstraintsConfig()
		).expirationDateOverrides["RIPEMD160"] shouldBe "2030-01-01"
	}
})

