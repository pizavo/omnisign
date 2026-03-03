package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Unit tests for [AlgorithmExpirationChecker].
 *
 * DSS `UpdateDate` semantics: `UpdateDate` is the date the cryptographic suites in the
 * validation policy were **last updated**.
 * - Algorithms whose expiry date is **after** `UpdateDate` were not yet known to be expired
 *   when the policy was written → use the lenient [expirationLevelAfterUpdate].
 * - Algorithms whose expiry date is **before or on** `UpdateDate` were already known to be
 *   broken when the policy was written → use the strict [expirationLevel].
 */
class AlgorithmExpirationCheckerTest {
	
	/**
	 * Default config: FAIL for old expiries, WARN for recent ones — explicitly set.
	 * No explicit [AlgorithmConstraintsConfig.policyUpdateDate] — uses
	 * [AlgorithmExpirationChecker.DEFAULT_UPDATE_DATE] (2024-10-13).
	 * RIPEMD160 (2014-08-01) and WHIRLPOOL (2020-12-01) both expired before
	 * the update date, so they use [expirationLevel] = FAIL.
	 */
	private val defaultConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.FAIL,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
	)
	
	private val allWarnConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.WARN,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
	)
	
	/**
	 * All-null config: every severity field is null, so [AlgorithmConstraintsConfig.DEFAULT]
	 * is used as fallback — FAIL for old expiries, WARN for recent ones.
	 */
	private val nullConfig = AlgorithmConstraintsConfig()
	
	private val allIgnoreConfig = AlgorithmConstraintsConfig(
		expirationLevel = AlgorithmConstraintLevel.IGNORE,
		expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
	)
	
	private val today = LocalDate(2026, 3, 1)
	
	// ── VALID cases ──────────────────────────────────────────────────────────
	
	@Test
	fun `non-expiring algorithms are always VALID`() {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		)
			.forEach { alg ->
				assertEquals(AlgorithmStatus.VALID, AlgorithmExpirationChecker.check(alg, defaultConfig, today))
			}
	}
	
	@Test
	fun `algorithm is VALID on exactly its expiration date`() {
		val expiryDay = LocalDate(2014, 8, 1)
		assertEquals(
			AlgorithmStatus.VALID,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, defaultConfig, expiryDay)
		)
	}
	
	@Test
	fun `algorithm is VALID the day before expiry`() {
		assertEquals(
			AlgorithmStatus.VALID,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, defaultConfig, LocalDate(2014, 7, 31))
		)
	}
	
	// ── Expiry before updateDate → expirationLevel ───────────────────────────
	
	@Test
	fun `RIPEMD160 expired before updateDate uses expirationLevel FAIL`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, defaultConfig, today)
		)
	}
	
	@Test
	fun `null config falls back to DEFAULT and produces EXPIRED_FAIL for RIPEMD160`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, nullConfig, today)
		)
	}
	
	@Test
	fun `DEFAULT config produces EXPIRED_FAIL for RIPEMD160`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, AlgorithmConstraintsConfig.DEFAULT, today)
		)
	}
	
	@Test
	fun `WHIRLPOOL expired before updateDate uses expirationLevel FAIL`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.WHIRLPOOL, defaultConfig, today)
		)
	}
	
	@Test
	fun `RIPEMD160 expired before updateDate uses expirationLevel WARN when set to WARN`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_WARN,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, allWarnConfig, today)
		)
	}
	
	@Test
	fun `RIPEMD160 expired before updateDate uses expirationLevel IGNORE as WARN`() {
		assertEquals(
			AlgorithmStatus.EXPIRED_WARN,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, allIgnoreConfig, today)
		)
	}
	
	// ── Expiry after updateDate → expirationLevelAfterUpdate ─────────────────
	
	@Test
	fun `algorithm expired after updateDate uses expirationLevelAfterUpdate WARN`() {
		// Override RIPEMD160 to expire on 2025-01-01 — after the default updateDate 2024-10-13.
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2025-01-01"))
		assertEquals(
			AlgorithmStatus.EXPIRED_WARN,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today)
		)
	}
	
	@Test
	fun `algorithm expired after updateDate uses expirationLevelAfterUpdate FAIL when set`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.FAIL,
			expirationDateOverrides = mapOf("RIPEMD160" to "2025-01-01")
		)
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today)
		)
	}
	
	@Test
	fun `algorithm expired on exactly updateDate uses expirationLevel not AfterUpdate`() {
		val updateDate = "2025-06-01"
		val config = defaultConfig.copy(
			policyUpdateDate = updateDate,
			expirationDateOverrides = mapOf("RIPEMD160" to updateDate)
		)
		// Expiry 2025-06-01 == updateDate 2025-06-01 → expirationLevel = FAIL
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today)
		)
	}
	
	@Test
	fun `custom constraintUpdateDate shifts which level is used`() {
		// Set updateDate to 2010-01-01 — before RIPEMD160's expiry of 2014-08-01.
		// RIPEMD160's expiry is now "after" the policy update date, meaning the policy
		// did not yet know about this expiry when it was last written → LevelAfterUpdate (WARN).
		val config = defaultConfig.copy(policyUpdateDate = "2010-01-01")
		assertEquals(
			AlgorithmStatus.EXPIRED_WARN,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today)
		)
	}
	
	// ── Override paths ────────────────────────────────────────────────────────
	
	@Test
	fun `override date in future makes bundled-expired algorithm VALID`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2050-01-01"))
		assertEquals(AlgorithmStatus.VALID, AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today))
	}
	
	@Test
	fun `override date on today makes algorithm VALID`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2026-03-01"))
		assertEquals(AlgorithmStatus.VALID, AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today))
	}
	
	@Test
	fun `override date in past before updateDate yields EXPIRED_FAIL`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2010-01-01"))
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.RIPEMD160, config, today)
		)
	}
	
	@Test
	fun `override on non-expiring algorithm with past date before updateDate yields EXPIRED_FAIL`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("SHA256" to "2020-01-01"))
		assertEquals(
			AlgorithmStatus.EXPIRED_FAIL,
			AlgorithmExpirationChecker.check(HashAlgorithm.SHA256, config, today)
		)
	}
	
	@Test
	fun `override on non-expiring algorithm with future date keeps VALID`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("SHA256" to "2040-01-01"))
		assertEquals(AlgorithmStatus.VALID, AlgorithmExpirationChecker.check(HashAlgorithm.SHA256, config, today))
	}
	
	// ── effectiveExpirationDate ───────────────────────────────────────────────
	
	@Test
	fun `effectiveExpirationDate returns override when present`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2040-06-15"))
		assertEquals(
			LocalDate(2040, 6, 15),
			AlgorithmExpirationChecker.effectiveExpirationDate(HashAlgorithm.RIPEMD160, config)
		)
	}
	
	@Test
	fun `effectiveExpirationDate falls back to bundled date when no override`() {
		assertEquals(
			HashAlgorithm.RIPEMD160.expirationDate,
			AlgorithmExpirationChecker.effectiveExpirationDate(HashAlgorithm.RIPEMD160, defaultConfig)
		)
	}
	
	@Test
	fun `effectiveExpirationDate returns null for non-expiring algo with no override`() {
		assertNull(AlgorithmExpirationChecker.effectiveExpirationDate(HashAlgorithm.SHA256, defaultConfig))
	}
	
	@Test
	fun `all non-expiring algorithms have no bundled expirationDate`() {
		listOf(
			HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512,
			HashAlgorithm.SHA3_256, HashAlgorithm.SHA3_384, HashAlgorithm.SHA3_512
		)
			.forEach { alg -> assertNull(alg.expirationDate, "$alg should have no bundled expiry") }
	}
	
	@Test
	fun `expiring algorithms have non-null bundled expirationDate`() {
		assertNotNull(HashAlgorithm.RIPEMD160.expirationDate)
		assertNotNull(HashAlgorithm.WHIRLPOOL.expirationDate)
	}
	
	// ── warningMessage ────────────────────────────────────────────────────────
	
	@Test
	fun `warningMessage references algorithm name and bundled expiry year`() {
		val msg = AlgorithmExpirationChecker.warningMessage(HashAlgorithm.RIPEMD160, defaultConfig)
		assertTrue(msg.contains("RIPEMD160"))
		assertTrue(msg.contains("2014"))
	}
	
	@Test
	fun `warningMessage uses override date when present`() {
		val config = defaultConfig.copy(expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01"))
		val msg = AlgorithmExpirationChecker.warningMessage(HashAlgorithm.RIPEMD160, config)
		assertTrue(msg.contains("2040"), "message should reference the override year, not the bundled year")
	}
}


