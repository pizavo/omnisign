package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
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
 * @property rawReports Pre-marshaled DSS report XML strings keyed by [RawReportFormat].
 *   Populated on JVM after validation so that the desktop/server UI can export them
 *   without re-running validation. Empty on non-JVM targets.
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
    val rawReports: Map<RawReportFormat, String> = emptyMap(),
) {
    /**
     * Highest [SignatureTrustTier] among all signatures that passed validation.
     *
     * Only signatures with [ValidationIndication.TOTAL_PASSED] are considered, so
     * a qualified but *invalid* signature does not contribute to the overall trust badge.
     * Returns [SignatureTrustTier.NOT_QUALIFIED] when no passed signature is qualified
     * or when [overallResult] is not [ValidationResult.VALID].
     */
    val overallTrustTier: SignatureTrustTier
        get() {
            if (overallResult != ValidationResult.VALID) return SignatureTrustTier.NOT_QUALIFIED

            return signatures
                .filter { it.indication == ValidationIndication.TOTAL_PASSED }
                .minOfOrNull { it.trustTier }
                ?: SignatureTrustTier.NOT_QUALIFIED
        }
}
