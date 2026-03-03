package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.SubContext
import eu.europa.esig.dss.model.policy.CryptographicSuite
import eu.europa.esig.dss.model.policy.ValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicyFactory
import eu.europa.esig.dss.policy.ValidationPolicyFacade
import eu.europa.esig.dss.policy.jaxb.Algo
import java.io.File

/**
 * Loads and optionally customises a DSS [ValidationPolicy].
 *
 * When [AlgorithmConstraintsConfig] is supplied the loaded ETSI policy's
 * `AlgoExpirationDate` constraint is patched in-place so the caller-supplied
 * severity levels, optional update-date override, and per-algorithm date overrides are
 * applied before validation.
 */
open class AdESPolicy {
	
	/**
	 * Load a [ValidationPolicy] from [policyFile] (or the DSS built-in ETSI default when
	 * null / non-existent) and apply [algorithmConstraints] on top.
	 */
	open fun load(
		policyFile: File?,
		algorithmConstraints: AlgorithmConstraintsConfig? = null
	): ValidationPolicy {
		val facade = ValidationPolicyFacade.newFacade()
		val policy = when {
			policyFile?.exists() == true -> facade.getValidationPolicy(policyFile)
			else -> EtsiValidationPolicyFactory().loadDefaultValidationPolicy()
		}
		if (algorithmConstraints != null) {
			applyAlgorithmConstraints(policy, algorithmConstraints)
		}
		return policy
	}
	
	/**
	 * Patch the algorithm expiration levels on every [CryptographicSuite] section exposed
	 * by [policy] via the public [CryptographicSuite] interface.
	 *
	 * Also applies [AlgorithmConstraintsConfig.policyUpdateDate] and
	 * [AlgorithmConstraintsConfig.expirationDateOverrides] directly on the JAXB
	 * `AlgoExpirationDate` object so that DSS validation honours the same dates as the
	 * pre-signing check.  For each override entry:
	 * - If a matching `Algo` element already exists in the list its `Date` attribute is updated.
	 * - If no matching element exists a new one is added so DSS tracks the algorithm at all.
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
}