package cz.pizavo.omnisign.cli.json

import cz.pizavo.omnisign.domain.model.validation.json.JsonValidationReport
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
 * JSON-serializable DTO for the result of a validation operation — a thin CLI envelope around the
 * shared [JsonValidationReport]. On success [report] carries the validation data and [error] is null;
 * on failure [report] is null and [error] describes what went wrong, so stdout stays parseable JSON
 * either way. The desktop and server surface failures through the UI and HTTP status instead, which
 * is why the shared report itself carries no `success`/`error` fields.
 *
 * @property success Whether the operation ran to completion — distinct from the signatures' verdict,
 *   which lives in [report]'s `overallResult`.
 * @property report The validation report; null when the operation failed.
 * @property rawReportPath Absolute path the raw DSS report was written to, when `--report-out` was given.
 * @property error Operation-level failure detail; null on success.
 */
@Serializable
data class JsonValidationResult(
	val success: Boolean,
	val report: JsonValidationReport? = null,
	val rawReportPath: String? = null,
	val error: JsonError? = null,
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
	val isQualified: Boolean? = null,
	val isQscd: Boolean? = null,
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
	val alreadyRunning: Boolean = false,
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

