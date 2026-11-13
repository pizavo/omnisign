package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies how [ValidationConfig] settings, [AlgorithmConstraintsConfig] among them, merge across the
 * global, profile, and operation layers.
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

	test("alertIfNotEuLotl resolves operation over profile over global, null inherits") {
		fun resolved(global: Boolean?, profile: Boolean?, operation: Boolean?): Boolean? =
			ResolvedConfig.resolve(
				global = GlobalConfig(
					defaultHashAlgorithm = HashAlgorithm.SHA256,
					defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
					validation = ValidationConfig(alertIfNotEuLotl = global),
				),
				profile = ProfileConfig(
					name = "p",
					validation = profile?.let { ValidationConfig(alertIfNotEuLotl = it) },
				),
				operationOverrides = operation?.let { OperationConfig(validation = ValidationConfig(alertIfNotEuLotl = it)) },
			).getOrElse { error -> throw AssertionError(error.message) }.validation.alertIfNotEuLotl

		resolved(global = true, profile = null, operation = null) shouldBe true
		resolved(global = true, profile = false, operation = null) shouldBe false
		resolved(global = false, profile = true, operation = null) shouldBe true
		resolved(global = false, profile = false, operation = true) shouldBe true
		resolved(global = null, profile = null, operation = null).shouldBeNull()
	}

	test("allowExpiredCertificate resolves operation over profile over global, null inherits") {
		fun resolved(global: Boolean?, profile: Boolean?, operation: Boolean?): Boolean? =
			ResolvedConfig.resolve(
				global = GlobalConfig(
					defaultHashAlgorithm = HashAlgorithm.SHA256,
					defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
					validation = ValidationConfig(allowExpiredCertificate = global),
				),
				profile = ProfileConfig(
					name = "p",
					validation = profile?.let { ValidationConfig(allowExpiredCertificate = it) },
				),
				operationOverrides = operation?.let { OperationConfig(validation = ValidationConfig(allowExpiredCertificate = it)) },
			).getOrElse { error -> throw AssertionError(error.message) }.validation.allowExpiredCertificate

		resolved(global = true, profile = null, operation = null) shouldBe true
		resolved(global = true, profile = false, operation = null) shouldBe false
		resolved(global = false, profile = true, operation = null) shouldBe true
		resolved(global = false, profile = false, operation = true) shouldBe true
		resolved(global = null, profile = null, operation = null).shouldBeNull()
	}

	/**
	 * Resolves the validation sections [global], [profile] and [operation] into the effective one; a
	 * `null` section is left out of its layer.
	 */
	fun resolvedValidation(
		global: ValidationConfig,
		profile: ValidationConfig? = null,
		operation: ValidationConfig? = null,
	): ValidationConfig = ResolvedConfig.resolve(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			validation = global,
		),
		profile = ProfileConfig(name = "p", validation = profile),
		operationOverrides = operation?.let { OperationConfig(validation = it) },
	).getOrElse { error -> throw AssertionError(error.message) }.validation

	test("policyType resolves operation over profile over global, null inherits, DEFAULT_ETSI last") {
		fun resolved(global: ValidationPolicyType?, profile: ValidationPolicyType?, operation: ValidationPolicyType?) =
			resolvedValidation(
				global = ValidationConfig(policyType = global),
				profile = profile?.let { ValidationConfig(policyType = it) },
				operation = operation?.let { ValidationConfig(policyType = it) },
			).policyType

		resolved(ValidationPolicyType.CUSTOM_FILE, null, null) shouldBe ValidationPolicyType.CUSTOM_FILE
		resolved(ValidationPolicyType.CUSTOM_FILE, ValidationPolicyType.DEFAULT_ETSI, null) shouldBe
				ValidationPolicyType.DEFAULT_ETSI
		resolved(ValidationPolicyType.DEFAULT_ETSI, ValidationPolicyType.CUSTOM_FILE, null) shouldBe
				ValidationPolicyType.CUSTOM_FILE
		resolved(ValidationPolicyType.DEFAULT_ETSI, ValidationPolicyType.DEFAULT_ETSI, ValidationPolicyType.CUSTOM_FILE) shouldBe
				ValidationPolicyType.CUSTOM_FILE
		resolved(null, null, null) shouldBe ValidationPolicyType.DEFAULT_ETSI
	}

	test("checkRevocation resolves operation over profile over global, null inherits, true last") {
		fun resolved(global: Boolean?, profile: Boolean?, operation: Boolean?) =
			resolvedValidation(
				global = ValidationConfig(checkRevocation = global),
				profile = profile?.let { ValidationConfig(checkRevocation = it) },
				operation = operation?.let { ValidationConfig(checkRevocation = it) },
			).checkRevocation

		resolved(global = false, profile = null, operation = null) shouldBe false
		resolved(global = false, profile = true, operation = null) shouldBe true
		resolved(global = true, profile = false, operation = null) shouldBe false
		resolved(global = true, profile = true, operation = false) shouldBe false
		resolved(global = null, profile = null, operation = null) shouldBe true
	}

	test("useEuLotl resolves operation over profile over global, null inherits, true last") {
		fun resolved(global: Boolean?, profile: Boolean?, operation: Boolean?) =
			resolvedValidation(
				global = ValidationConfig(useEuLotl = global),
				profile = profile?.let { ValidationConfig(useEuLotl = it) },
				operation = operation?.let { ValidationConfig(useEuLotl = it) },
			).useEuLotl

		resolved(global = false, profile = null, operation = null) shouldBe false
		resolved(global = false, profile = true, operation = null) shouldBe true
		resolved(global = true, profile = false, operation = null) shouldBe false
		resolved(global = true, profile = true, operation = false) shouldBe false
		resolved(global = null, profile = null, operation = null) shouldBe true
	}

	test("a profile section holding only trusted lists keeps the global policy, revocation checking and EU LOTL") {
		val resolved = resolvedValidation(
			global = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				checkRevocation = false,
				useEuLotl = false,
			),
			profile = ValidationConfig(
				customTrustedLists = listOf(CustomTrustedListConfig(name = "corp", source = "file:///corp.xml")),
			),
		)

		resolved.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		resolved.checkRevocation shouldBe false
		resolved.useEuLotl shouldBe false
		resolved.customTrustedLists.map { it.name } shouldBe listOf("corp")
	}

	test("a profile section holding only algorithm constraints keeps the global policy, revocation checking and EU LOTL") {
		val resolved = resolvedValidation(
			global = ValidationConfig(
				policyType = ValidationPolicyType.CUSTOM_FILE,
				checkRevocation = false,
				useEuLotl = false,
			),
			profile = ValidationConfig(
				algorithmConstraints = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
			),
		)

		resolved.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		resolved.checkRevocation shouldBe false
		resolved.useEuLotl shouldBe false
	}

	test("an operation override of the policy alone keeps revocation checking and the EU LOTL of the layers below") {
		val resolved = resolvedValidation(
			global = ValidationConfig(checkRevocation = false, useEuLotl = false),
			profile = ValidationConfig(useEuLotl = true),
			operation = ValidationConfig(policyType = ValidationPolicyType.DEFAULT_ETSI),
		)

		resolved.policyType shouldBe ValidationPolicyType.DEFAULT_ETSI
		resolved.checkRevocation shouldBe false
		resolved.useEuLotl shouldBe true
	}
})
