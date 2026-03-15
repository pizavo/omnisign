package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.Level
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import java.util.*

/**
 * Verifies algorithm constraint application to the DSS validation policy.
 */
class AdESPolicyTest : FunSpec({
	
	val policy = AdESPolicy()
	
	fun signatureSuite(loaded: EtsiValidationPolicy) =
		loaded.getSignatureCryptographicConstraint(Context.SIGNATURE)
	
	test("load returns non-null policy with no file and no constraints") {
		policy.load(null, null).shouldNotBeNull()
	}
	
	test("load applies FAIL expiration level to signature crypto suite") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.FAIL
		suite.algorithmsExpirationDateAfterUpdateLevel shouldBe Level.WARN
	}
	
	test("load applies WARN expiration level to signature crypto suite") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.WARN
		suite.algorithmsExpirationDateAfterUpdateLevel shouldBe Level.INFORM
	}
	
	test("load applies IGNORE expiration level to signature crypto suite") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.IGNORE,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
		)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.IGNORE
		suite.algorithmsExpirationDateAfterUpdateLevel shouldBe Level.IGNORE
	}
	
	test("load applies IGNORE to counter-signature crypto suite") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.IGNORE,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.IGNORE
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val suite = loaded.getSignatureCryptographicConstraint(Context.COUNTER_SIGNATURE).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.IGNORE
		suite.algorithmsExpirationDateAfterUpdateLevel shouldBe Level.IGNORE
	}
	
	test("load without constraints preserves DSS default expiry level FAIL") {
		signatureSuite(policy.load(null, null) as EtsiValidationPolicy)
			.shouldNotBeNull()
			.algorithmsExpirationDateLevel shouldBe Level.FAIL
	}
	
	test("load without constraints preserves DSS default expiry level after update WARN") {
		signatureSuite(policy.load(null, null) as EtsiValidationPolicy)
			.shouldNotBeNull()
			.algorithmsExpirationDateAfterUpdateLevel shouldBe Level.WARN
	}
	
	test("load applies policyUpdateDate to policy update date when stamped") {
		val stampDate = LocalDate(2030, 1, 1)
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN
		).stampedToday(stampDate)
		val suite = signatureSuite(policy.load(null, config) as EtsiValidationPolicy).shouldNotBeNull()
		val updateDate = suite.cryptographicSuiteUpdateDate.shouldNotBeNull()
		val cal = Calendar.getInstance().also { it.time = updateDate }
		cal.get(Calendar.YEAR) shouldBe 2030
		cal.get(Calendar.MONTH) shouldBe Calendar.JANUARY
		cal.get(Calendar.DAY_OF_MONTH) shouldBe 1
	}
	
	test("load applies expirationDateOverrides to JAXB Algo entries") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			expirationDateOverrides = mapOf("RIPEMD160" to "2040-01-01")
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val algoEntry = loaded.cryptographic?.algoExpirationDate?.algos
			?.find { it.value == "RIPEMD160" }
		algoEntry.shouldNotBeNull()
		algoEntry.date shouldBe "2040-01-01"
	}
	
	test("load inserts new Algo entry when override targets algorithm not in default policy") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			expirationDateOverrides = mapOf("SHA256" to "2035-06-01")
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val algoEntry = loaded.cryptographic?.algoExpirationDate?.algos
			?.find { it.value == "SHA256" }
		algoEntry.shouldNotBeNull()
		algoEntry.date shouldBe "2035-06-01"
	}
})

