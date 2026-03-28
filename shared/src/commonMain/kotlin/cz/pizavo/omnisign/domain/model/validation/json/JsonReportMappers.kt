package cz.pizavo.omnisign.domain.model.validation.json

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureValidationResult
import cz.pizavo.omnisign.domain.model.validation.TimestampValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import cz.pizavo.omnisign.domain.model.validation.ValidationReport

/**
 * Convert a domain [ValidationReport] to a [JsonValidationReport] DTO
 * suitable for JSON serialization.
 */
fun ValidationReport.toJsonReport(): JsonValidationReport {
    val passed = signatures.count { it.indication == ValidationIndication.TOTAL_PASSED }
    val failed = signatures.count { it.indication == ValidationIndication.TOTAL_FAILED }
    val indeterminate = signatures.count { it.indication == ValidationIndication.INDETERMINATE }

    return JsonValidationReport(
        documentName = documentName,
        validationTime = validationTime.toString(),
        overallResult = overallResult.name,
        signatures = signatures.map { it.toJsonReport() },
        timestamps = timestamps.map { it.toJsonReport() },
        summary = JsonValidationSummary(
            total = signatures.size,
            passed = passed,
            failed = failed,
            indeterminate = indeterminate,
        ),
        tlWarnings = tlWarnings,
    )
}

/**
 * Convert a domain [SignatureValidationResult] to a [JsonSignatureReport] DTO.
 */
fun SignatureValidationResult.toJsonReport(): JsonSignatureReport =
    JsonSignatureReport(
        signatureId = signatureId,
        indication = indication.name,
        subIndication = subIndication,
        signedBy = signedBy,
        signatureLevel = signatureLevel,
        signatureTime = signatureTime.toString(),
        qualification = signatureQualification,
        hashAlgorithm = hashAlgorithm,
        encryptionAlgorithm = encryptionAlgorithm,
        certificate = certificate.toJsonReport(),
        errors = errors,
        warnings = warnings,
        infos = infos,
        qualificationErrors = qualificationErrors,
        qualificationWarnings = qualificationWarnings,
        qualificationInfos = qualificationInfos,
        timestamps = timestamps.map { it.toJsonReport() },
    )

/**
 * Convert a domain [CertificateInfo] to a [JsonCertificateReport] DTO.
 */
fun CertificateInfo.toJsonReport(): JsonCertificateReport =
    JsonCertificateReport(
        subjectDN = subjectDN,
        issuerDN = issuerDN,
        serialNumber = serialNumber,
        validFrom = validFrom.toString(),
        validTo = validTo.toString(),
        keyUsages = keyUsages,
        isQualified = isQualified,
        publicKeyAlgorithm = publicKeyAlgorithm,
        sha256Fingerprint = sha256Fingerprint,
    )

/**
 * Convert a domain [TimestampValidationResult] to a [JsonTimestampReport] DTO.
 */
fun TimestampValidationResult.toJsonReport(): JsonTimestampReport =
    JsonTimestampReport(
        timestampId = timestampId,
        type = type,
        indication = indication.name,
        subIndication = subIndication,
        productionTime = productionTime.toString(),
        qualification = qualification,
        tsaSubjectDN = tsaSubjectDN,
        errors = errors,
        warnings = warnings,
        infos = infos,
    )

