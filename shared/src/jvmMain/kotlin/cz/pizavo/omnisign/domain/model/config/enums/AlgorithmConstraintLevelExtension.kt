package cz.pizavo.omnisign.domain.model.config.enums

import eu.europa.esig.dss.enumerations.Level as DssLevel

/**
 * Maps the domain [AlgorithmConstraintLevel] to the DSS [eu.europa.esig.dss.enumerations.Level]
 * constant used when configuring cryptographic constraint levels in the validation policy.
 */
fun AlgorithmConstraintLevel.toDss(): DssLevel = when (this) {
	AlgorithmConstraintLevel.FAIL -> DssLevel.FAIL
	AlgorithmConstraintLevel.WARN -> DssLevel.WARN
	AlgorithmConstraintLevel.INFORM -> DssLevel.INFORM
	AlgorithmConstraintLevel.IGNORE -> DssLevel.IGNORE
}

