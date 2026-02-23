package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo

/**
 * Validation result for a single signature.
 *
 * @property signatureId DSS internal identifier for this signature.
 * @property indication Overall validation indication.
 * @property subIndication Optional sub-indication providing additional detail.
 * @property errors Validation errors for this signature.
 * @property warnings Validation warnings for this signature.
 * @property infos Informational messages for this signature.
 * @property signedBy Human-readable signer name from the certificate.
 * @property signatureLevel PAdES signature level (e.g. "PAdES-BASELINE-LTA").
 * @property signatureTime Best signature time as determined by DSS.
 * @property certificate Signing certificate details.
 * @property signatureQualification eIDAS qualification of the signature (e.g. "QESig", "AdESig").
 * @property hashAlgorithm Digest algorithm used in the signature (e.g. "SHA256").
 * @property encryptionAlgorithm Encryption algorithm of the signing key (e.g. "RSA", "ECDSA").
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
    val certificate: CertificateInfo,
    val signatureQualification: String? = null,
    val hashAlgorithm: String? = null,
    val encryptionAlgorithm: String? = null,
)

