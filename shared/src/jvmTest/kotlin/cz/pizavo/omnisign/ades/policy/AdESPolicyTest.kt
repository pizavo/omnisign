package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.Level
import eu.europa.esig.dss.enumerations.SignatureAlgorithm
import eu.europa.esig.dss.model.policy.CryptographicSuite
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import eu.europa.esig.dss.validation.policy.CryptographicSuiteUtils
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import java.util.*
import kotlin.time.Instant

/**
 * Verifies algorithm constraint application and disabled-algorithm enforcement
 * on the DSS validation policy.
 */
class AdESPolicyTest : FunSpec({
	
	val policy = AdESPolicy()
	
	fun signatureSuite(loaded: EtsiValidationPolicy) =
		loaded.getSignatureCryptographicConstraint(Context.SIGNATURE)

	/**
	 * The encryption algorithms [suite] still accepts. DSS no longer lists them directly: it
	 * pairs every acceptable encryption algorithm with every acceptable digest into signature
	 * algorithms, so an encryption algorithm is accepted exactly when some pair uses it.
	 */
	fun encryptionAlgorithms(suite: CryptographicSuite) =
		suite.acceptableSignatureAlgorithms.keys.mapTo(mutableSetOf()) { it.encryptionAlgorithm }

	/**
	 * The signature cryptographic suite DSS builds from the default policy with [overrides] applied.
	 */
	fun suiteWith(overrides: Map<String, String>): CryptographicSuite =
		policy.cryptographicSuite(AlgorithmConstraintsConfig(expirationDateOverrides = overrides)).shouldNotBeNull()
	
	/**
	 * The day, in UTC, a [keySize]-bit key of [algorithm] stops being acceptable under [overrides],
	 * as DSS itself decides it from every key-size tier, or `null` when it never does.
	 */
	fun expiry(algorithm: SignatureAlgorithm, keySize: Int, overrides: Map<String, String> = emptyMap()): String? =
		CryptographicSuiteUtils.getExpirationDate(suiteWith(overrides), algorithm, keySize)
			?.let { Instant.fromEpochMilliseconds(it.time).toString().take(10) }
	
	/**
	 * The day, in UTC, [algorithm] stops being acceptable under [overrides], as DSS decides it, or
	 * `null` when it never does.
	 */
	fun digestExpiry(algorithm: DigestAlgorithm, overrides: Map<String, String>): String? =
		CryptographicSuiteUtils.getExpirationDate(suiteWith(overrides), algorithm)
			?.let { Instant.fromEpochMilliseconds(it.time).toString().take(10) }
	
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
		(policy.load(null, null) as EtsiValidationPolicy).cryptographic?.algoExpirationDate?.algos.orEmpty()
			.none { it.value == "MD2" } shouldBe true
		val config = AlgorithmConstraintsConfig(
			expirationLevel = AlgorithmConstraintLevel.FAIL,
			expirationLevelAfterUpdate = AlgorithmConstraintLevel.WARN,
			expirationDateOverrides = mapOf("MD2" to "2035-06-01")
		)
		val loaded = policy.load(null, config) as EtsiValidationPolicy
		val algoEntry = loaded.cryptographic?.algoExpirationDate?.algos
			?.find { it.value == "MD2" }
		algoEntry.shouldNotBeNull()
		algoEntry.date shouldBe "2035-06-01"
	}
	
	test("without overrides DSS dates RSA and ECDSA keys by their size") {
		expiry(SignatureAlgorithm.RSA_SHA256, 1024) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048) shouldBe "2027-01-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072) shouldBe "2035-01-01"
		expiry(SignatureAlgorithm.ECDSA_SHA256, 224) shouldBe "2021-10-01"
		expiry(SignatureAlgorithm.ECDSA_SHA256, 256).shouldBeNull()
	}
	
	test("an override for a hash algorithm sets its date, later or earlier") {
		digestExpiry(DigestAlgorithm.SHA1, mapOf("SHA1" to "2030-12-31")) shouldBe "2030-12-31"
		digestExpiry(DigestAlgorithm.SHA224, mapOf("SHA224" to "2026-01-01")) shouldBe "2026-01-01"
	}
	
	test("an override with a key size moves only that tier, for the keys it is the latest tier of") {
		val overrides = mapOf("RSA:1900" to "2030-12-31")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 1024, overrides) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2030-12-31"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2035-01-01"
	}
	
	test("an override with a key size can bring its tier forward") {
		val overrides = mapOf("RSA:1900" to "2026-06-01")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2026-06-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2035-01-01"
	}
	
	test("an override with a key size the policy has no tier for adds that tier") {
		val overrides = mapOf("RSA:2048" to "2030-12-31")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 1024, overrides) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2000, overrides) shouldBe "2027-01-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2030-12-31"
	}
	
	test("an override without a key size never accepts a weak key again") {
		val overrides = mapOf("RSA" to "2030-12-31")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 1024, overrides) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2027-01-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2030-12-31"
	}
	
	test("an override without a key size brings every later tier forward to its date") {
		val overrides = mapOf("RSA" to "2026-06-01")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 1024, overrides) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2026-06-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2026-06-01"
	}
	
	test("an override without a key size dates a tier that never expired") {
		val overrides = mapOf("ECDSA" to "2030-01-01")
		
		expiry(SignatureAlgorithm.ECDSA_SHA256, 224, overrides) shouldBe "2021-10-01"
		expiry(SignatureAlgorithm.ECDSA_SHA256, 256, overrides) shouldBe "2030-01-01"
	}
	
	test("an override without a key size also bounds the tiers that overrides with one set, in either order") {
		val capFirst = linkedMapOf("RSA" to "2026-06-01", "RSA:3000" to "2040-01-01", "RSA:4096" to "2045-01-01")
		val capLast = linkedMapOf("RSA:3000" to "2040-01-01", "RSA:4096" to "2045-01-01", "RSA" to "2026-06-01")
		
		listOf(capFirst, capLast).forEach { overrides ->
			expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2026-06-01"
			expiry(SignatureAlgorithm.RSA_SHA256, 4096, overrides) shouldBe "2026-06-01"
		}
	}
	
	test("an override whose key size is not a positive number changes nothing") {
		val overrides = mapOf("RSA:abc" to "2040-01-01", "RSA:0" to "2040-01-01", "RSA:" to "2040-01-01")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 1024, overrides) shouldBe "2019-10-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2027-01-01"
		val loaded = policy.load(null, AlgorithmConstraintsConfig(expirationDateOverrides = overrides)) as EtsiValidationPolicy
		loaded.cryptographic?.algoExpirationDate?.algos.orEmpty()
			.none { it.value?.contains(':') == true || it.size == 0 } shouldBe true
	}
	
	test("an override without a key size whose date the policy cannot read caps nothing") {
		val overrides = mapOf("RSA" to "31.12.2026")
		
		expiry(SignatureAlgorithm.RSA_SHA256, 2048, overrides) shouldBe "2027-01-01"
		expiry(SignatureAlgorithm.RSA_SHA256, 3072, overrides) shouldBe "2035-01-01"
	}
	
	test("disabled hash algorithm is removed from acceptable digest algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms.keys shouldNotContain DigestAlgorithm.SHA256
	}
	
	test("non-disabled hash algorithms remain in acceptable digest algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.RIPEMD160),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms.keys shouldContain DigestAlgorithm.SHA256
		suite.acceptableDigestAlgorithms.keys shouldContain DigestAlgorithm.SHA384
	}
	
	test("multiple disabled hash algorithms are all removed") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256, HashAlgorithm.SHA384),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms.keys shouldNotContain DigestAlgorithm.SHA256
		suite.acceptableDigestAlgorithms.keys shouldNotContain DigestAlgorithm.SHA384
		suite.acceptableDigestAlgorithms.keys shouldContain DigestAlgorithm.SHA512
	}
	
	test("disabled encryption algorithm is removed from acceptable encryption algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		encryptionAlgorithms(suite) shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
	}
	
	test("non-disabled encryption algorithms remain in acceptable encryption algorithms") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		encryptionAlgorithms(suite) shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.RSA
		encryptionAlgorithms(suite) shouldContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.ECDSA
	}
	
	test("multiple disabled encryption algorithms are all removed") {
		val loaded = policy.load(
			policyFile = null,
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA, EncryptionAlgorithm.RSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		encryptionAlgorithms(suite) shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
		encryptionAlgorithms(suite) shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.RSA
		encryptionAlgorithms(suite) shouldContain
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
		encryptionAlgorithms(suite) shouldNotContain
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
		suite.acceptableDigestAlgorithms.keys shouldNotContain DigestAlgorithm.SHA256
	}
	
	test("disabled hash and encryption algorithms can be applied simultaneously") {
		val loaded = policy.load(
			policyFile = null,
			disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
			disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		) as EtsiValidationPolicy
		val suite = signatureSuite(loaded).shouldNotBeNull()
		suite.acceptableDigestAlgorithms.keys shouldNotContain DigestAlgorithm.SHA256
		encryptionAlgorithms(suite) shouldNotContain
				eu.europa.esig.dss.enumerations.EncryptionAlgorithm.DSA
		suite.acceptableDigestAlgorithms.keys shouldContain DigestAlgorithm.SHA512
		encryptionAlgorithms(suite) shouldContain
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
		loadedSuite.acceptableSignatureAlgorithms shouldBe baselineSuite.acceptableSignatureAlgorithms
	}
})

