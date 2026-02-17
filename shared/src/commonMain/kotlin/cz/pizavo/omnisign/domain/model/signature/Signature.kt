package cz.pizavo.omnisign.domain.model.signature

/**
 * Represents a digital signature within a document.
 */
data class Signature(
    val id: String,
    val signedBy: String,
    val signatureDate: String,
    val signatureLevel: String,
    val isValid: Boolean,
    val certificateInfo: CertificateInfo,
    val timestamps: List<TimestampInfo> = emptyList()
)

