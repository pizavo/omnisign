package cz.pizavo.omnisign.domain.model.validation

/**
 * Validation report result.
 */
data class ValidationReport(
    val documentName: String,
    val validationTime: String,
    val overallResult: ValidationResult,
    val signatures: List<SignatureValidationResult>,
    val timestamps: List<TimestampValidationResult> = emptyList()
)

