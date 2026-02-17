package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a signing operation.
 */
data class SigningResult(
    val outputFile: String,
    val signatureId: String,
    val signatureLevel: String
)

