package cz.pizavo.omnisign.cli.json

import cz.pizavo.omnisign.domain.model.error.OperationError
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.validation.*
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
 */
fun SigningResult.toJsonResult(): JsonSigningResult =
    JsonSigningResult(
        success = true,
        outputFile = outputFile,
        signatureId = signatureId,
        signatureLevel = signatureLevel,
        warnings = warnings,
    )

/**
 * Convert a domain [ArchivingResult] to a success [JsonExtensionResult] DTO.
 */
fun ArchivingResult.toJsonResult(): JsonExtensionResult =
    JsonExtensionResult(
        success = true,
        outputFile = outputFile,
        newLevel = newSignatureLevel,
    )

/**
 * Convert a domain [ValidationReport] to a [JsonValidationResult] DTO.
 */
fun ValidationReport.toJsonResult(rawReportPath: String? = null): JsonValidationResult {
    val passed = signatures.count { it.indication == ValidationIndication.TOTAL_PASSED }
    val failed = signatures.count { it.indication == ValidationIndication.TOTAL_FAILED }
    val indeterminate = signatures.count { it.indication == ValidationIndication.INDETERMINATE }

    return JsonValidationResult(
        success = true,
        documentName = documentName,
        validationTime = validationTime,
        overallResult = overallResult.name,
        signatures = signatures.map { it.toJson() },
        timestamps = timestamps.map { it.toJson() },
        summary = JsonValidationSummary(
            total = signatures.size,
            passed = passed,
            failed = failed,
            indeterminate = indeterminate,
        ),
        rawReportPath = rawReportPath,
        tlWarnings = tlWarnings,
    )
}

/**
 * Convert a domain [SignatureValidationResult] to a [JsonSignatureResult] DTO.
 */
private fun SignatureValidationResult.toJson(): JsonSignatureResult =
    JsonSignatureResult(
        signatureId = signatureId,
        indication = indication.name,
        subIndication = subIndication,
        signedBy = signedBy,
        signatureLevel = signatureLevel,
        signatureTime = signatureTime,
        qualification = signatureQualification,
        hashAlgorithm = hashAlgorithm,
        encryptionAlgorithm = encryptionAlgorithm,
        certificate = certificate.toJson(),
        errors = errors,
        warnings = warnings,
        infos = infos,
    )

/**
 * Convert a domain [cz.pizavo.omnisign.domain.model.signature.CertificateInfo] to a [JsonCertificateInfo] DTO.
 */
private fun cz.pizavo.omnisign.domain.model.signature.CertificateInfo.toJson(): JsonCertificateInfo =
    JsonCertificateInfo(
        subjectDN = subjectDN,
        issuerDN = issuerDN,
        serialNumber = serialNumber,
        validFrom = validFrom,
        validTo = validTo,
        keyUsages = keyUsages,
        isQualified = isQualified,
        publicKeyAlgorithm = publicKeyAlgorithm,
        sha256Fingerprint = sha256Fingerprint,
    )

/**
 * Convert a domain [TimestampValidationResult] to a [JsonTimestampResult] DTO.
 */
private fun TimestampValidationResult.toJson(): JsonTimestampResult =
    JsonTimestampResult(
        timestampId = timestampId,
        type = type,
        indication = indication.name,
        subIndication = subIndication,
        productionTime = productionTime,
        qualification = qualification,
        tsaSubjectDN = tsaSubjectDN,
        errors = errors,
        warnings = warnings,
        infos = infos,
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
                validFrom = cert.validFrom,
                validTo = cert.validTo,
                tokenType = cert.tokenType,
                keyUsages = cert.keyUsages,
            )
        },
        tokenWarnings = tokenWarnings.map { w ->
            JsonTokenWarning(
                tokenId = w.tokenId,
                tokenName = w.tokenName,
                message = w.message,
                details = w.details,
            )
        },
    )

