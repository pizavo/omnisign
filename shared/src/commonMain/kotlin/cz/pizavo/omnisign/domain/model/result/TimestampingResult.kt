package cz.pizavo.omnisign.domain.model.result

/**
 * Result of a timestamping operation.
 */
data class TimestampingResult(
    val outputFile: String,
    val timestampTime: String
)

