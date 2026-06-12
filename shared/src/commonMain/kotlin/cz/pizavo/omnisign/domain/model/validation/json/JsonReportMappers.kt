package cz.pizavo.omnisign.domain.model.validation.json

import cz.pizavo.omnisign.domain.model.signature.CertificateChainLink
import cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection
import cz.pizavo.omnisign.domain.model.signature.CertificateField
import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import cz.pizavo.omnisign.domain.model.signature.CertificateTrustSource
import cz.pizavo.omnisign.domain.model.validation.RevocationInfo
import cz.pizavo.omnisign.domain.model.validation.SignatureTrustTier
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
        overallTrustTier = overallTrustTier.takeIf { it != SignatureTrustTier.NOT_QUALIFIED }?.name,
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
        trustTier = trustTier.name,
        euLotlBacked = euLotlBacked,
        hashAlgorithm = hashAlgorithm,
        encryptionAlgorithm = encryptionAlgorithm,
        certificate = certificate.toJsonReport(),
        revocations = revocations.map { it.toJsonReport() },
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
        chain = chain.map { it.toJsonReport() },
    )

/**
 * Convert a domain [CertificateChainLink] to a [JsonCertificateChainLink] DTO. The raw DER bytes are
 * not carried — the report surfaces only the parsed detail.
 */
fun CertificateChainLink.toJsonReport(): JsonCertificateChainLink =
    JsonCertificateChainLink(
        commonName = commonName,
        subjectDN = subjectDN,
        selfSigned = selfSigned,
        trustedVia = trustedVia.map { it.toJsonReport() },
        details = details.map { it.toJsonReport() },
    )

/**
 * Convert a domain [CertificateTrustSource] to a [JsonTrustSource] DTO.
 */
fun CertificateTrustSource.toJsonReport(): JsonTrustSource = when (this) {
    is CertificateTrustSource.TrustedList -> JsonTrustSource(type = "TRUSTED_LIST", name = name)
    CertificateTrustSource.GlobalStore -> JsonTrustSource(type = "GLOBAL_STORE")
    is CertificateTrustSource.ProfileStore -> JsonTrustSource(type = "PROFILE_STORE", name = profileName)
}

/**
 * Convert a domain [CertificateDetailSection] to a [JsonCertificateDetailSection] DTO.
 */
fun CertificateDetailSection.toJsonReport(): JsonCertificateDetailSection =
    JsonCertificateDetailSection(
        title = title,
        fields = fields.map { it.toJsonReport() },
    )

/**
 * Convert a domain [CertificateField] to a [JsonCertificateField] DTO.
 */
fun CertificateField.toJsonReport(): JsonCertificateField =
    JsonCertificateField(
        label = label,
        value = value,
    )

/**
 * Convert a domain [RevocationInfo] to a [JsonRevocationReport] DTO. Times are emitted as ISO-8601
 * strings; absent times stay `null`.
 */
fun RevocationInfo.toJsonReport(): JsonRevocationReport =
    JsonRevocationReport(
        method = method,
        status = status,
        revoked = revoked,
        embedded = embedded,
        sealedByTimestamp = sealedByTimestamp,
        origin = origin,
        sourceUrl = sourceUrl,
        producedAt = producedAt?.toString(),
        thisUpdate = thisUpdate?.toString(),
        nextUpdate = nextUpdate?.toString(),
        revocationDate = revocationDate?.toString(),
        reason = reason,
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
        euLotlBacked = euLotlBacked,
        errors = errors,
        warnings = warnings,
        infos = infos,
        chain = chain.map { it.toJsonReport() },
    )

