package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.Level
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import java.util.*

/**
 * Verifies algorithm constraint application and disabled-algorithm enforcement
 * on the DSS validation policy.
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
	
	test("disabled hash algorithm is removed from acceptable digest algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms shouldNotContain DigestAlgorithm.SHA256
	}
	
	test("non-disabled hash algorithms remain in acceptable digest algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.RIPEMD160),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms shouldContain DigestAlgorithm.SHA256
		suite.acceptableDigestAlgorithms shouldContain DigestAlgorithm.SHA384
	}
	
	test("multiple disabled hash algorithms are all removed") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256, HashAlgorithm.SHA384),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms shouldNotContain DigestAlgorithm.SHA256
		suite.acceptableDigestAlgorithms shouldNotContain DigestAlgorithm.SHA384
		suite.acceptableDigestAlgorithms shouldContain DigestAlgorithm.SHA512
	}
	
	test("disabled encryption algorithm is removed from acceptable encryption algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableEncryptionAlgorithms shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
	}
	
	test("non-disabled encryption algorithms remain in acceptable encryption algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableEncryptionAlgorithms shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.RSA
		suite.acceptableEncryptionAlgorithms shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.ECDSA
	}
	
	test("multiple disabled encryption algorithms are all removed") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA, EncryptionAlgorithm.RSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableEncryptionAlgorithms shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
		suite.acceptableEncryptionAlgorithms shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.RSA
		suite.acceptableEncryptionAlgorithms shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.ECDSA
	}
	
	test("disabled encryption algorithm is removed from global JAXB CryptographicConstraint") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.RSA),
		) as EtsiValidationPolicy
		val globalAlgos = loaded.cryptographic?.acceptableEncryptionAlgo?.algos
		globalAlgos.shouldNotBeNull()
		globalAlgos.none { it.value == "RSA" } shouldBe true
	}
	
	test("disabled encryption and constraints can be applied together") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM,
		)
		val loaded = policy.load(
			policyFile = null,
			algorithmConstraints = config,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.WARN
		suite.acceptableEncryptionAlgorithms shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
	}
	
	test("disabled hash algorithms are removed from global JAXB CryptographicConstraint") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
		) as EtsiValidationPolicy
		val globalAlgos = loaded.cryptographic?.acceptableDigestAlgo?.algos
		globalAlgos.shouldNotBeNull()
		globalAlgos.none { it.value == "SHA256" } shouldBe true
	}
	
	test("disabled hash and constraints can be applied together") {
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.WARN,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.INFORM,
		)
		val loaded = policy.load(
			policyFile = null,
			algorithmConstraints = config,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.algorithmsExpirationDateLevel shouldBe Level.WARN
		suite.acceptableDigestAlgorithms shouldNotContain DigestAlgorithm.SHA256
	}
	
	test("disabled hash and encryption algorithms can be applied simultaneously") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms shouldNotContain DigestAlgorithm.SHA256
		suite.acceptableEncryptionAlgorithms shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
		suite.acceptableDigestAlgorithms shouldContain DigestAlgorithm.SHA512
		suite.acceptableEncryptionAlgorithms shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.RSA
	}
	
	test("empty disabled sets leave policy unchanged") {
		val baseline = policy.load(null) as EtsiValidationPolicy
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = emptySet(),
			disabledEncryptionAlgorithms = emptySet(),
		) as EtsiValidationPolicy
		val baselineSuite = signatureSuite(baseline).shouldNotBeNull()
		val loadedSuite = signatureSuite(loaded).shouldNotBeNull()
		loadedSuite.acceptableDigestAlgorithms shouldBe baselineSuite.acceptableDigestAlgorithms
		loadedSuite.acceptableEncryptionAlgorithms shouldBe baselineSuite.acceptableEncryptionAlgorithms
	}
})

