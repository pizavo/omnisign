package cz.pizavo.omnisign.cli.json

import kotlinx.serialization.Serializable

/**
 * JSON-serializable DTO for the result of a signing operation.
 */
@Serializable
data class JsonSigningResult(
	val success: Boolean,
	val outputFile: String? = null,
	val signatureId: String? = null,
	val signatureLevel: String? = null,
	val warnings: List<String> = emptyList(),
	val rawWarnings: List<String> = emptyList(),
	val error: JsonError? = null,
)

/**
 * JSON-serializable DTO for the result of a validation operation.
 */
@Serializable
data class JsonValidationResult(
	val success: Boolean,
	val documentName: String? = null,
	val validationTime: String? = null,
	val overallResult: String? = null,
	val signatures: List<JsonSignatureResult> = emptyList(),
	val timestamps: List<JsonTimestampResult> = emptyList(),
	val summary: JsonValidationSummary? = null,
	val tlWarnings: List<String> = emptyList(),
	val rawReportPath: String? = null,
	val error: JsonError? = null,
)

/**
 * JSON-serializable DTO for a single signature within a validation report.
 *
 * @property errors AdES validation errors.
 * @property warnings AdES validation warnings.
 * @property infos AdES informational messages.
 * @property qualificationErrors eIDAS qualification errors.
 * @property qualificationWarnings eIDAS qualification warnings.
 * @property qualificationInfos eIDAS qualification informational messages.
 */
@Serializable
data class JsonSignatureResult(
	val signatureId: String,
	val indication: String,
	val subIndication: String? = null,
	val signedBy: String,
	val signatureLevel: String,
	val signatureTime: String,
	val qualification: String? = null,
	val hashAlgorithm: String? = null,
	val encryptionAlgorithm: String? = null,
	val certificate: JsonCertificateInfo,
	val errors: List<String> = emptyList(),
	val warnings: List<String> = emptyList(),
	val infos: List<String> = emptyList(),
	val qualificationErrors: List<String> = emptyList(),
	val qualificationWarnings: List<String> = emptyList(),
	val qualificationInfos: List<String> = emptyList(),
	val timestamps: List<JsonTimestampResult> = emptyList(),
)

/**
 * JSON-serializable DTO for certificate information.
 */
@Serializable
data class JsonCertificateInfo(
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
 * JSON-serializable DTO for a single timestamp within a validation report.
 */
@Serializable
data class JsonTimestampResult(
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
 * JSON-serializable DTO for the validation summary.
 */
@Serializable
data class JsonValidationSummary(
	val total: Int,
	val passed: Int,
	val failed: Int,
	val indeterminate: Int,
)

/**
 * JSON-serializable DTO for the result of a timestamp/extension operation.
 */
@Serializable
data class JsonExtensionResult(
	val success: Boolean,
	val outputFile: String? = null,
	val newLevel: String? = null,
	val warnings: List<String> = emptyList(),
	val rawWarnings: List<String> = emptyList(),
	val error: JsonError? = null,
)

/**
 * JSON-serializable DTO for certificate listing output.
 */
@Serializable
data class JsonCertificateList(
	val success: Boolean,
	val certificates: List<JsonAvailableCertificate> = emptyList(),
	val tokenWarnings: List<JsonTokenWarning> = emptyList(),
	val error: JsonError? = null,
)

/**
 * JSON-serializable DTO for a per-token warning within a certificate listing.
 */
@Serializable
data class JsonTokenWarning(
	val tokenId: String,
	val tokenName: String,
	val message: String,
	val details: String? = null,
)

/**
 * JSON-serializable DTO for an available certificate from a token.
 */
@Serializable
data class JsonAvailableCertificate(
	val alias: String,
	val subjectDN: String,
	val issuerDN: String,
	val validFrom: String,
	val validTo: String,
	val tokenType: String,
	val keyUsages: List<String> = emptyList(),
)

/**
 * JSON-serializable DTO for the result of a renewal run.
 */
@Serializable
data class JsonRenewalResult(
	val success: Boolean,
	val checked: Int = 0,
	val renewed: Int = 0,
	val skipped: Int = 0,
	val errors: Int = 0,
	val dryRun: Boolean = false,
	val jobs: List<JsonRenewalJobResult> = emptyList(),
	val error: JsonError? = null,
)

/**
 * JSON-serializable DTO for a single renewal job result.
 */
@Serializable
data class JsonRenewalJobResult(
	val name: String,
	val files: List<JsonRenewalFileResult> = emptyList(),
)

/**
 * JSON-serializable DTO for the renewal status of a single file.
 */
@Serializable
data class JsonRenewalFileResult(
	val path: String,
	val status: String,
	val message: String? = null,
	val warnings: List<String> = emptyList(),
)

/**
 * JSON-serializable DTO for an operation error.
 */
@Serializable
data class JsonError(
	val message: String,
	val details: String? = null,
	val cause: String? = null,
)

