package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig

/**
 * Parameters for validation operation.
 */
data class ValidationParameters(
    val inputFile: String,
    val customPolicyPath: String? = null,
    val resolvedConfig: ResolvedConfig? = null
)



