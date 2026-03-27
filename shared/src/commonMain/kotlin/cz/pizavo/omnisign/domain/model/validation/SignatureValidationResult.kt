package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.signature.CertificateInfo
import kotlin.time.Instant

/**
 * Validation result for a single signature.
 *
 * @property signatureId DSS internal identifier for this signature.
 * @property indication Overall validation indication.
 * @property subIndication Optional sub-indication providing additional detail.
 * @property errors AdES validation errors for this signature.
 * @property warnings AdES validation warnings for this signature.
 * @property infos AdES informational messages for this signature.
 * @property qualificationErrors eIDAS qualification errors (e.g. certificate not on a trusted list).
 * @property qualificationWarnings eIDAS qualification warnings (e.g. unexpected key-usage).
 * @property qualificationInfos eIDAS qualification informational messages.
 * @property signedBy Human-readable signer name from the certificate.
 * @property signatureLevel PAdES signature level (e.g. "PAdES-BASELINE-LTA").
 * @property signatureTime Point in time of the best signature time as determined by DSS.
 * @property certificate Signing certificate details.
 * @property signatureQualification eIDAS qualification of the signature (e.g. "QESig", "AdESig").
 * @property hashAlgorithm Digest algorithm used in the signature (e.g. "SHA256").
 * @property encryptionAlgorithm Encryption algorithm of the signing key (e.g. "RSA", "ECDSA").
 * @property timestamps Timestamp tokens embedded within or covering this signature (e.g., signature timestamps).
 */
data class SignatureValidationResult(
    val signatureId: String,
    val indication: ValidationIndication,
    val subIndication: String? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList(),
    val qualificationErrors: List<String> = emptyList(),
    val qualificationWarnings: List<String> = emptyList(),
    val qualificationInfos: List<String> = emptyList(),
    val signedBy: String,
    val signatureLevel: String,
    val signatureTime: Instant,
    val certificate: CertificateInfo,
    val signatureQualification: String? = null,
    val hashAlgorithm: String? = null,
    val encryptionAlgorithm: String? = null,
    val timestamps: List<TimestampValidationResult> = emptyList(),
)
