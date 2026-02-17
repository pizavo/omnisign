package cz.pizavo.omnisign.domain.model.parameters

/**
 * Parameters for LTA extension operation.
 */
data class ArchivingParameters(
    val inputFile: String,
    val outputFile: String,
    val extendToLTA: Boolean = true
)

