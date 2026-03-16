package cz.pizavo.omnisign.domain.model.validation

/**
 * Validation report result.
 */
data class ValidationReport(
    val documentName: String,
    val validationTime: String,
    val overallResult: ValidationResult,
    val signatures: List<SignatureValidationResult>,
    val timestamps: List<TimestampValidationResult> = emptyList(),
    /**
     * User-readable notices about trusted list loading issues encountered during validation.
     * A non-empty list means one or more member-state trusted lists could not be refreshed,
     * which may affect qualification assessment but does not invalidate the signature itself.
     */
    val tlWarnings: List<String> = emptyList(),
)

