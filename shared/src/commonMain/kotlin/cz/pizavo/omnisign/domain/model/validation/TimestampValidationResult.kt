
package cz.pizavo.omnisign.domain.model.validation

/**
 * Validation result for a timestamp.
 */
data class TimestampValidationResult(
    val timestampId: String,
    val indication: ValidationIndication,
    val productionTime: String,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

