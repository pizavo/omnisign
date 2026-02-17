package cz.pizavo.omnisign.domain.model.parameters

/**
 * Parameters for validation operation.
 */
data class ValidationParameters(
    val inputFile: String,
    val customPolicyPath: String? = null
)

