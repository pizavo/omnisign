package cz.pizavo.omnisign.domain.model.validation

import kotlin.time.Instant

/**
 * Validation report result.
 *
 * @property documentName Name of the validated document.
 * @property validationTime Point in time at which validation was executed.
 * @property overallResult Aggregated validation outcome.
 * @property signatures Per-signature validation results.
 * @property timestamps Document-level timestamp validation results not associated with a specific signature.
 * @property tlWarnings User-readable notices about trusted list loading issues encountered during validation.
 */
data class ValidationReport(
    val documentName: String,
    val validationTime: Instant,
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
