package cz.pizavo.omnisign.domain.model.result

/**
 * Result of an archiving operation.
 */
data class ArchivingResult(
    val outputFile: String,
    val newSignatureLevel: String
)

