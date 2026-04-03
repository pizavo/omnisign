package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

/**
 * Verifies [AlgorithmExpirationChecker] behaviour including DSS `UpdateDate` semantics.
 *
 * `UpdateDate` is the date the cryptographic suites in the validation policy were **last updated**.
 * - Algorithms whose expiry date is **after** `UpdateDate` → use lenient [AlgorithmConstraintsConfig.expirationLevelAfterUpdate].
 * - Algorithms whose expiry date is **before or on** `UpdateDate` → use strict [AlgorithmConstraintsConfig.expirationLevel].
 */
class AlgorithmExpirationCheckerTest : FunSpec({
	
	val checker = AlgorithmExpirationChecker()
	
	val defaultConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.FAIL,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
	)
	
	val allWarnConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.WARN,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
	)
	
	val nullConfig = AlgorithmConstraintsConfig()
	
	val allIgnoreConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.IGNORE,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
	)
	
	val today = LocalDate(2026, 3, 1)
	
	test("non-expiring algorithms are always VALID") {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		).forEach { alg ->
			checker.check(alg, defaultConfig, today) shouldBe AlgorithmStatus.VALID
		}
	}
	
	test("algorithm is VALID on exactly its expiration date") {
		checker.check(
			HashAlgorithm.RIPEMD160, defaultConfig, LocalDate(2014, 8, 1)
		) shouldBe AlgorithmStatus.VALID
	}
	
	test("algorithm is VALID the day before expiry") {
		checker.check(
			HashAlgorithm.RIPEMD160, defaultConfig, LocalDate(2014, 7, 31)
		) shouldBe AlgorithmStatus.VALID
	}
	
	test("RIPEMD160 expired before updateDate uses expirationLevel FAIL") {
		checker.check(HashAlgorithm.RIPEMD160, defaultConfig, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("null config falls back to DEFAULT and produces EXPIRED_FAIL for RIPEMD160") {
		checker.check(HashAlgorithm.RIPEMD160, nullConfig, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("DEFAULT config produces EXPIRED_FAIL for RIPEMD160") {
		checker.check(
			HashAlgorithm.RIPEMD160, AlgorithmConstraintsConfig.DEFAULT, today
		) shouldBe AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("WHIRLPOOL expired before updateDate uses expirationLevel FAIL") {
		checker.check(HashAlgorithm.WHIRLPOOL, defaultConfig, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("RIPEMD160 expired before updateDate uses expirationLevel WARN when set to WARN") {
		checker.check(HashAlgorithm.RIPEMD160, allWarnConfig, today) shouldBe
			AlgorithmStatus.EXPIRED_WARN
	}
	
	test("RIPEMD160 expired before updateDate uses expirationLevel IGNORE as WARN") {
		checker.check(HashAlgorithm.RIPEMD160, allIgnoreConfig, today) shouldBe
			AlgorithmStatus.EXPIRED_WARN
	}
	
	test("algorithm expired after updateDate uses expirationLevelAfterUpdate WARN") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2025-01-01"))
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe
			AlgorithmStatus.EXPIRED_WARN
	}
	
	test("algorithm expired after updateDate uses expirationLevelAfterUpdate FAIL when set") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.FAIL,
			expirationDateOverrides = mapOf("RIPEMD160" to "2025-01-01")
		)
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("algorithm expired on exactly updateDate uses expirationLevel not AfterUpdate") {
		val updateDate = "2025-06-01"
		val config = defaultConfig.copy(
			policyUpdateDate = updateDate,
			expirationDateOverrides = mapOf("RIPEMD160" to updateDate)
		)
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("custom constraintUpdateDate shifts which level is used") {
		val config = defaultConfig.copy(policyUpdateDate = "2010-01-01")
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe
			AlgorithmStatus.EXPIRED_WARN
	}
	
	test("override date in future makes bundled-expired algorithm VALID") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2050-01-01"))
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe AlgorithmStatus.VALID
	}
	
	test("override date on today makes algorithm VALID") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2026-03-01"))
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe AlgorithmStatus.VALID
	}
	
	test("override date in past before updateDate yields EXPIRED_FAIL") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2010-01-01"))
		checker.check(HashAlgorithm.RIPEMD160, config, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("override on non-expiring algorithm with past date before updateDate yields EXPIRED_FAIL") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("SHA256" to "2020-01-01"))
		checker.check(HashAlgorithm.SHA256, config, today) shouldBe
			AlgorithmStatus.EXPIRED_FAIL
	}
	
	test("override on non-expiring algorithm with future date keeps VALID") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("SHA256" to "2040-01-01"))
		checker.check(HashAlgorithm.SHA256, config, today) shouldBe AlgorithmStatus.VALID
	}
	
	test("effectiveExpirationDate returns override when present") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2040-06-15"))
		checker.effectiveExpirationDate(HashAlgorithm.RIPEMD160, config) shouldBe
			LocalDate(2040, 6, 15)
	}
	
	test("effectiveExpirationDate falls back to bundled date when no override") {
		checker.effectiveExpirationDate(HashAlgorithm.RIPEMD160, defaultConfig) shouldBe
			HashAlgorithm.RIPEMD160.expirationDate
	}
	
	test("effectiveExpirationDate returns null for non-expiring algo with no override") {
		checker.effectiveExpirationDate(HashAlgorithm.SHA256, defaultConfig).shouldBeNull()
	}
	
	test("all non-expiring algorithms have no bundled expirationDate") {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		).forEach { alg -> alg.expirationDate.shouldBeNull() }
	}
	
	test("expiring algorithms have non-null bundled expirationDate") {
		HashAlgorithm.RIPEMD160.expirationDate.shouldNotBeNull()
		HashAlgorithm.WHIRLPOOL.expirationDate.shouldNotBeNull()
	}
	
	test("warningMessage references algorithm name and bundled expiry year") {
		val msg = checker.warningMessage(HashAlgorithm.RIPEMD160, defaultConfig)
		msg shouldContain "RIPEMD160"
		msg shouldContain "2014"
	}
	
	test("warningMessage uses override date when present") {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01"))
		checker.warningMessage(HashAlgorithm.RIPEMD160, config) shouldContain "2040"
	}
})
