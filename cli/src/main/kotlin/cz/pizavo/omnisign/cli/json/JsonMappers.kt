package cz.pizavo.omnisign.cli.json

import cz.pizavo.omnisign.domain.model.error.OperationError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.validation.*
import cz.pizavo.omnisign.domain.model.validation.json.toJsonReport
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult

/**
 * Convert a domain [OperationError] to a [JsonError] DTO.
 */
fun OperationError.toJsonError(): JsonError =
	JsonError(
		message = message,
		details = details,
		cause = cause?.message,
	)

/**
 * Convert a domain [SigningResult] to a success [JsonSigningResult] DTO.
 *
 * @param outputFile Absolute path the CLI wrote the signed bytes to, surfaced in the JSON
 *   summary so machine-readable consumers can locate the file. The domain [SigningResult]
 *   carries only the bytes and the suggested document name — the on-disk location is
 *   chosen at the CLI boundary.
 */
fun SigningResult.toJsonResult(outputFile: String): JsonSigningResult =
	JsonSigningResult(
		success = true,
		outputFile = outputFile,
		signatureId = signatureId,
		signatureLevel = signatureLevel,
		warnings = warnings,
		rawWarnings = rawWarnings,
	)

/**
 * Convert a domain [ArchivingResult] to a success [JsonExtensionResult] DTO.
 *
 * @param outputFile Absolute path the CLI wrote the extended bytes to, surfaced in the JSON
 *   summary so machine-readable consumers can locate the file. The domain [ArchivingResult]
 *   carries only the bytes and the suggested document name — the on-disk location is chosen
 *   at the CLI boundary.
 */
fun ArchivingResult.toJsonResult(outputFile: String): JsonExtensionResult =
	JsonExtensionResult(
		success = true,
		outputFile = outputFile,
		newLevel = newSignatureLevel,
		warnings = warnings,
		rawWarnings = rawWarnings,
	)

/**
 * Convert a domain [ValidationReport] to a success [JsonValidationResult] — the shared
 * [cz.pizavo.omnisign.domain.model.validation.json.JsonValidationReport] wrapped in the CLI's success
 * envelope.
 */
fun ValidationReport.toJsonResult(rawReportPath: String? = null): JsonValidationResult =
	JsonValidationResult(
		success = true,
		report = toJsonReport(),
		rawReportPath = rawReportPath,
	)

/**
 * Convert a [CertificateDiscoveryResult] to a success [JsonCertificateList] DTO.
 */
fun CertificateDiscoveryResult.toJsonCertificateList(): JsonCertificateList =
	JsonCertificateList(
		success = true,
		certificates = certificates.map { cert ->
			JsonAvailableCertificate(
				alias = cert.alias,
				subjectDN = cert.subjectDN,
				issuerDN = cert.issuerDN,
				validFrom = cert.validFrom.toString(),
				validTo = cert.validTo.toString(),
				tokenType = cert.tokenType,
				keyUsages = cert.keyUsages,
				isQualified = cert.isQualified,
				isQscd = cert.isQscd,
			)
		},
		tokenWarnings = tokenWarnings.map { w ->
			JsonTokenWarning(
				tokenId = w.tokenId,
				tokenName = w.tokenName,
				message = w.message.english(),
				details = w.details,
			)
		},
	)

