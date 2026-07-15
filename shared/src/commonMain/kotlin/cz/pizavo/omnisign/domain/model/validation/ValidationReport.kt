package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import kotlinx.serialization.Serializable
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
 *   Populated on JVM after validation only for the formats the caller requested via
 *   [cz.pizavo.omnisign.domain.model.parameters.ValidationParameters.rawReportFormats],
 *   so the desktop UI can export them without re-running validation. Empty when no
 *   formats were requested (the CLI by default; the server when its `formats` multipart
 *   field is absent) and on non-JVM targets.
 */
@Serializable
data class ValidationReport(
    val documentName: String,
    val validationTime: Instant,
    val overallResult: ValidationResult,
    val signatures: List<SignatureValidationResult>,
    val timestamps: List<TimestampValidationResult> = emptyList(),
    /**
     * User-readable notices about trusted list loading issues encountered during validation, plus
     * any revocation-coverage warnings DSS raised without naming a signature or timestamp of the
     * document. A non-empty list means one or more member-state trusted lists could not be refreshed
     * (which may affect qualification assessment but does not invalidate the signature itself), or a
     * gap could not be attributed to a specific chain. Each entry is a [LocalizableText] —
     * OmniSign-authored notices as [LocalizableText.Keyed], propagated text as [LocalizableText.Literal].
     */
    val tlWarnings: List<LocalizableText> = emptyList(),
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

    /**
     * Whether every signature's trust anchor is on the EU LOTL (or a national trusted list that is
     * a member of it): true when there is at least one signature and all of them are
     * [SignatureValidationResult.euLotlBacked]. Purely reflects trust-anchor membership — independent
     * of the qualification tier ([overallTrustTier]) and of overall validity ([overallResult]).
     */
    val overallEuLotlBacked: Boolean
        get() = signatures.isNotEmpty() && signatures.all { it.euLotlBacked }
}
