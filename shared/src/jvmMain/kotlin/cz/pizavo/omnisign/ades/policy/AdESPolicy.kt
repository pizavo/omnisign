package cz.pizavo.omnisign.ades.policy

import eu.europa.esig.dss.model.policy.ValidationPolicy
import eu.europa.esig.dss.policy.EtsiValidationPolicyFactory
import eu.europa.esig.dss.policy.ValidationPolicyFacade
import java.io.File

open class AdESPolicy {
	open fun load(policyFile: File?): ValidationPolicy {
		val facade = ValidationPolicyFacade.newFacade()
		
		return when {
			policyFile?.exists() ?: false -> facade.getValidationPolicy(policyFile)
			else -> EtsiValidationPolicyFactory().loadDefaultValidationPolicy()
		}
	}
}