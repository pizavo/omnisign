package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.SubContext
import eu.europa.esig.dss.model.policy.CryptographicSuite
import eu.europa.esig.dss.model.policy.ValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicyFactory
import eu.europa.esig.dss.policy.ValidationPolicyFacade
import eu.europa.esig.dss.policy.jaxb.Algo
import eu.europa.esig.dss.policy.jaxb.CryptographicConstraint
import java.io.File

/**
 * Loads and optionally customizes a DSS [ValidationPolicy].
 *
 * When [AlgorithmConstraintsConfig] is supplied the loaded ETSI policy's
 * `AlgoExpirationDate` constraint is patched in-place so the caller-supplied
 * severity levels, optional update-date override, and per-algorithm date overrides are
 * applied before validation.
 *
 * When disabled algorithm sets are supplied, the matching entries are removed from
 * every `AcceptableDigestAlgo` and `AcceptableEncryptionAlgo` list in the JAXB
 * policy tree so that DSS itself reports them as non-compliant during validation.
 */
open class AdESPolicy {
	
	/**
	 * Load a [ValidationPolicy] from [policyFile] (or the DSS built-in ETSI default when
	 * null / non-existent), apply [algorithmConstraints] on top, and strip any
	 * [disabledHashAlgorithms] / [disabledEncryptionAlgorithms] from every
	 * `AcceptableDigestAlgo` / `AcceptableEncryptionAlgo` section of the policy.
	 */
	open fun load(
		policyFile: File?,
		algorithmConstraints: AlgorithmConstraintsConfig? = null,
		disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
		disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	): ValidationPolicy {
		val facade = ValidationPolicyFacade.newFacade()
		val policy = when {
			policyFile?.exists() == true -> facade.getValidationPolicy(policyFile)
			else -> EtsiValidationPolicyFactory().loadDefaultValidationPolicy()
		}
		if (algorithmConstraints != null) {
			applyAlgorithmConstraints(policy, algorithmConstraints)
		}
		if (disabledHashAlgorithms.isNotEmpty() || disabledEncryptionAlgorithms.isNotEmpty()) {
			removeDisabledAlgorithms(policy, disabledHashAlgorithms, disabledEncryptionAlgorithms)
		}
		return policy
	}
	
	/**
	 * Patch the algorithm expiration levels on every [CryptographicSuite] section exposed
	 * by [policy] via the public [CryptographicSuite] interface.
	 *
	 * Also applies [AlgorithmConstraintsConfig.policyUpdateDate] and
	 * [AlgorithmConstraintsConfig.expirationDateOverrides] directly on the JAXB
	 * `AlgoExpirationDate` object so that DSS validation honors the same dates as the
	 * pre-signing check.  For each override entry:
	 * - If a matching `Algo` element already exists in the list its `Date` attribute is updated.
	 * - If no matching element exists, a new one is added, so DSS tracks the algorithm completely.
	 */
	private fun applyAlgorithmConstraints(
		policy: ValidationPolicy,
		config: AlgorithmConstraintsConfig
	) {
		if (policy !is EtsiValidationPolicy) return
		
		val dssLevel = (config.expirationLevel ?: AlgorithmConstraintsConfig.DEFAULT.expirationLevel!!).toDss()
		val dssLevelAfterUpdate = (config.expirationLevelAfterUpdate
			?: AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate!!).toDss()
		
		collectCryptographicSuites(policy).forEach { suite ->
			suite.algorithmsExpirationDateLevel = dssLevel
			suite.setAlgorithmsExpirationTimeAfterPolicyUpdateLevel(dssLevelAfterUpdate)
		}
		
		val algoExpirationDate = policy.cryptographic?.algoExpirationDate ?: return
		
		config.policyUpdateDate?.let { algoExpirationDate.updateDate = it }
		
		if (config.expirationDateOverrides.isNotEmpty()) {
			applyPerAlgorithmDateOverrides(algoExpirationDate.algos, config.expirationDateOverrides)
		}
	}
	
	/**
	 * Update or insert per-algorithm `Date` attributes in the live JAXB [algos] list.
	 *
	 * The [algos] list is mutable (backed by JAXB's lazy-init `ArrayList`) so modifications
	 * propagate immediately to the DSS policy evaluation.
	 */
	private fun applyPerAlgorithmDateOverrides(
		algos: MutableList<Algo>,
		overrides: Map<String, String>
	) {
		overrides.forEach { (algoName, dateString) ->
			val existing = algos.find { it.value == algoName }
			if (existing != null) {
				existing.date = dateString
			} else {
				algos += Algo().also { it.value = algoName; it.date = dateString }
			}
		}
	}
	
	/**
	 * Collect all non-null [CryptographicSuite] instances from the standard DSS
	 * policy sections that are relevant for PAdES validation.
	 */
	private fun collectCryptographicSuites(policy: EtsiValidationPolicy): List<CryptographicSuite> =
		listOfNotNull(
			runCatching { policy.getSignatureCryptographicConstraint(Context.SIGNATURE) }.getOrNull(),
			runCatching { policy.getSignatureCryptographicConstraint(Context.COUNTER_SIGNATURE) }.getOrNull(),
			runCatching {
				policy.getCertificateCryptographicConstraint(
					Context.SIGNATURE,
					SubContext.SIGNING_CERT
				)
			}.getOrNull(),
			runCatching { policy.getEvidenceRecordCryptographicConstraint() }.getOrNull()
		)
	
	/**
	 * Remove disabled hash and encryption algorithms from the JAXB `AcceptableDigestAlgo`
	 * and `AcceptableEncryptionAlgo` lists across the entire policy tree.
	 *
	 * DSS resolves accepted algorithms hierarchically (context-specific overrides the
	 * global `Cryptographic` section), so this method collects every reachable JAXB
	 * [CryptographicConstraint] and strips disabled entries from all of them.
	 */
	private fun removeDisabledAlgorithms(
		policy: ValidationPolicy,
		disabledHash: Set<HashAlgorithm>,
		disabledEncryption: Set<EncryptionAlgorithm>,
	) {
		if (policy !is EtsiValidationPolicy) return
		
		val disabledDigestNames = disabledHash.map { it.dssName }.toSet()
		val disabledEncryptionNames = disabledEncryption.map { it.dssName }.toSet()
		
		collectJaxbCryptographicConstraints(policy).forEach { constraint ->
			if (disabledDigestNames.isNotEmpty()) {
				constraint.acceptableDigestAlgo?.algos?.removeAll { it.value in disabledDigestNames }
			}
			if (disabledEncryptionNames.isNotEmpty()) {
				constraint.acceptableEncryptionAlgo?.algos?.removeAll { it.value in disabledEncryptionNames }
			}
		}
	}
	
	/**
	 * Collect every non-null JAXB [CryptographicConstraint] reachable from the policy.
	 *
	 * Covers:
	 * - Global `Cryptographic` section
	 * - Signature / CounterSignature → BasicSignatureConstraints → CryptographicConstraint
	 * - Signature / CounterSignature → BasicSignatureConstraints → SigningCertificate → CryptographicConstraint
	 * - Signature / CounterSignature → BasicSignatureConstraints → CACertificate → CryptographicConstraint
	 * - Timestamp → BasicSignatureConstraints (and its nested cert constraints)
	 * - Revocation → BasicSignatureConstraints (and its nested cert constraints)
	 * - EvidenceRecord → BasicSignatureConstraints (and its nested cert constraints)
	 */
	private fun collectJaxbCryptographicConstraints(policy: EtsiValidationPolicy): List<CryptographicConstraint> {
		val result = mutableListOf<CryptographicConstraint>()
		
		policy.cryptographic?.let { result += it }
		
		fun addFromBasicConstraints(basic: eu.europa.esig.dss.policy.jaxb.BasicSignatureConstraints?) {
			basic?.cryptographic?.let { result += it }
			basic?.signingCertificate?.cryptographic?.let { result += it }
			basic?.caCertificate?.cryptographic?.let { result += it }
		}
		
		addFromBasicConstraints(policy.signatureConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.counterSignatureConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.timestampConstraints?.basicSignatureConstraints)
		addFromBasicConstraints(policy.revocationConstraints?.basicSignatureConstraints)
		policy.evidenceRecordConstraints?.cryptographic?.let { result += it }
		
		return result
	}
}