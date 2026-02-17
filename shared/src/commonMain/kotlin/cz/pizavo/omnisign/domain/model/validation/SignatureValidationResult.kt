package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo

/**
 * Validation result for a single signature.
 */
data class SignatureValidationResult(
    val signatureId: String,
    val indication: ValidationIndication,
    val subIndication: String? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList(),
    val signedBy: String,
    val signatureLevel: String,
    val signatureTime: String,
    val certificate: CertificateInfo
)

