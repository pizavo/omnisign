package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.Level
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import kotlinx.datetime.LocalDate
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [AdESPolicy].
 *
 * Verifies that algorithm constraint configuration is correctly applied to the
 * loaded DSS validation policy, and that the default policy loads without error.
 * Assertions are made via the public [eu.europa.esig.dss.model.policy.CryptographicSuite]
 * interface rather than internal JAXB fields.
 */
class AdESPolicyTest {
	
	private val policy = AdESPolicy()
	
	private fun signatureSuite(loaded: EtsiValidationPolicy) =
		loaded.getSignatureCryptographicConstraint(Context.SIGNATURE)
	
	@Test
	fun `load returns non-null policy with no file and no constraints`() {
		val loaded = policy.load(null, null)
		assertNotNull(loaded)
	}
	
	@Test
	fun `load applies FAIL expiration level to signature crypto suite`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy)
		assertNotNull(suite)
		assertEquals(Level.FAIL, suite.algorithmsExpirationDateLevel)
		assertEquals(Level.WARN, suite.algorithmsExpirationDateAfterUpdateLevel)
	}
	
	@Test
	fun `load applies WARN expiration level to signature crypto suite`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy)
		assertNotNull(suite)
		assertEquals(Level.WARN, suite.algorithmsExpirationDateLevel)
		assertEquals(Level.INFORM, suite.algorithmsExpirationDateAfterUpdateLevel)
	}
	
	@Test
	fun `load applies IGNORE expiration level to signature crypto suite`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.IGNORE,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy)
		assertNotNull(suite)
		assertEquals(Level.IGNORE, suite.algorithmsExpirationDateLevel)
		assertEquals(Level.IGNORE, suite.algorithmsExpirationDateAfterUpdateLevel)
	}
	
	@Test
	fun `load applies IGNORE to counter-signature crypto suite`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.IGNORE,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val suite = loaded.getSignatureCryptographicConstraint(Context.COUNTER_SIGNATURE)
		assertNotNull(suite)
		assertEquals(Level.IGNORE, suite.algorithmsExpirationDateLevel)
		assertEquals(Level.IGNORE, suite.algorithmsExpirationDateAfterUpdateLevel)
	}
	
	@Test
	fun `load without constraints preserves DSS default expiry level FAIL`() {
		val suite = signatureSuite(policy.load(null, null) as EtsiValidationPolicy)
		assertNotNull(suite)
		assertEquals(Level.FAIL, suite.algorithmsExpirationDateLevel)
	}
	
	@Test
	fun `load without constraints preserves DSS default expiry level after update WARN`() {
		val suite = signatureSuite(policy.load(null, null) as EtsiValidationPolicy)
		assertNotNull(suite)
		assertEquals(Level.WARN, suite.algorithmsExpirationDateAfterUpdateLevel)
	}
	
	@Test
	fun `load applies policyUpdateDate to policy update date when stamped`() {
		val stampDate = LocalDate(2030, 1, 1)
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
		).stampedToday(stampDate)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy)
		assertNotNull(suite)
		val updateDate = suite.cryptographicSuiteUpdateDate
		assertNotNull(updateDate)
		val cal = Calendar.getInstance().also { it.time = updateDate }
		assertEquals(2030, cal.get(Calendar.YEAR))
		assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
		assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
	}
	
	@Test
	fun `load applies expirationDateOverrides to JAXB Algo entries`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01")
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val algoEntry = loaded.cryptographic?.algoExpirationDate?.algos
			?.find { it.value == "RIPEMD160" }
		assertNotNull(algoEntry, "RIPEMD160 Algo entry should exist in the policy")
		assertEquals("2040-01-01", algoEntry.date)
	}
	
	@Test
	fun `load inserts new Algo entry when override targets algorithm not in default policy`() {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			expirationDateOverrides = mapOf("SHA256" to "2035-06-01")
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val algoEntry = loaded.cryptographic?.algoExpirationDate?.algos
			?.find { it.value == "SHA256" }
		assertNotNull(algoEntry, "SHA256 Algo entry should have been inserted")
		assertEquals("2035-06-01", algoEntry.date)
	}
}
