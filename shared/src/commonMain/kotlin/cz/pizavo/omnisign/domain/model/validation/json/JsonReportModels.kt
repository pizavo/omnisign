package cz.pizavo.omnisign.domain.model.validation.json

import kotlinx.serialization.Serializable

/**
 * Serializable DTO representing a complete [cz.pizavo.omnisign.domain.model.validation.ValidationReport].
 *
 * This is a platform-agnostic, `kotlinx.serialization`-backed model intended for
 * JSON export from both CLI and desktop/server UIs. It contains only the
 * validation data — no CLI-specific envelope fields such as `success` or `error`.
 *
 * @property documentName Name of the validated document.
 * @property validationTime ISO-8601 timestamp of the validation execution.
 * @property overallResult Aggregated validation outcome name.
 * @property signatures Per-signature validation results.
 * @property timestamps Document-level timestamp results.
 * @property summary Counters of passed / failed / indeterminate signatures.
 * @property tlWarnings Trusted-list loading warnings, if any.
 */
@Serializable
data class JsonValidationReport(
    val documentName: String,
    val validationTime: String,
    val overallResult: String,
    val signatures: List<JsonSignatureReport> = emptyList(),
    val timestamps: List<JsonTimestampReport> = emptyList(),
    val summary: JsonValidationSummary? = null,
    val tlWarnings: List<String> = emptyList(),
)

/**
 * Serializable DTO for a single signature within a validation report.
 *
 * @property signatureId DSS internal signature identifier.
 * @property indication Overall validation indication.
 * @property subIndication Optional sub-indication detail.
 * @property signedBy Human-readable signer name.
 * @property signatureLevel PAdES signature level string.
 * @property signatureTime ISO-8601 best signature time.
 * @property qualification eIDAS qualification label.
 * @property trustTier eIDAS trust tier classification name (e.g. "QUALIFIED_QSCD", "QUALIFIED", "NOT_QUALIFIED").
 * @property hashAlgorithm Digest algorithm name.
 * @property encryptionAlgorithm Encryption algorithm name.
 * @property certificate Signing certificate details.
 * @property errors AdES validation errors.
 * @property warnings AdES validation warnings.
 * @property infos AdES informational messages.
 * @property qualificationErrors eIDAS qualification errors.
 * @property qualificationWarnings eIDAS qualification warnings.
 * @property qualificationInfos eIDAS qualification informational messages.
 * @property timestamps Timestamp tokens embedded within this signature.
 */
@Serializable
data class JsonSignatureReport(
    val signatureId: String,
    val indication: String,
    val subIndication: String? = null,
    val signedBy: String,
    val signatureLevel: String,
    val signatureTime: String,
    val qualification: String? = null,
    val trustTier: String? = null,
    val hashAlgorithm: String? = null,
    val encryptionAlgorithm: String? = null,
    val certificate: JsonCertificateReport,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList(),
    val qualificationErrors: List<String> = emptyList(),
    val qualificationWarnings: List<String> = emptyList(),
    val qualificationInfos: List<String> = emptyList(),
    val timestamps: List<JsonTimestampReport> = emptyList(),
)

/**
 * Serializable DTO for certificate information.
 *
 * @property subjectDN Certificate subject distinguished name.
 * @property issuerDN Certificate issuer distinguished name.
 * @property serialNumber Certificate serial number (hex).
 * @property validFrom ISO-8601 start of validity.
 * @property validTo ISO-8601 end of validity.
 * @property keyUsages Key usage extension values.
 * @property isQualified Whether the certificate is qualified under eIDAS.
 * @property publicKeyAlgorithm Public key algorithm name.
 * @property sha256Fingerprint Colon-separated hex SHA-256 fingerprint.
 */
@Serializable
data class JsonCertificateReport(
    val subjectDN: String,
    val issuerDN: String,
    val serialNumber: String,
    val validFrom: String,
    val validTo: String,
    val keyUsages: List<String> = emptyList(),
    val isQualified: Boolean = false,
    val publicKeyAlgorithm: String? = null,
    val sha256Fingerprint: String? = null,
)

/**
 * Serializable DTO for a single timestamp within a validation report.
 *
 * @property timestampId DSS internal timestamp token identifier.
 * @property type Human-readable timestamp type.
 * @property indication Overall validation indication for this timestamp.
 * @property subIndication Optional sub-indication detail.
 * @property productionTime ISO-8601 timestamp production time.
 * @property qualification Qualification level label.
 * @property tsaSubjectDN TSA certificate subject distinguished name.
 * @property errors Validation errors.
 * @property warnings Validation warnings.
 * @property infos Informational messages.
 */
@Serializable
data class JsonTimestampReport(
    val timestampId: String,
    val type: String,
    val indication: String,
    val subIndication: String? = null,
    val productionTime: String,
    val qualification: String? = null,
    val tsaSubjectDN: String? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList(),
)

/**
 * Serializable DTO for the validation summary counters.
 *
 * @property total Total number of signatures.
 * @property passed Number of signatures with TOTAL_PASSED indication.
 * @property failed Number of signatures with TOTAL_FAILED indication.
 * @property indeterminate Number of signatures with INDETERMINATE indication.
 */
@Serializable
data class JsonValidationSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val indeterminate: Int,
)

