
package cz.pizavo.omnisign.domain.model.signature

/**
 * Timestamp information.
 */
data class TimestampInfo(
    val type: String,
    val productionTime: String,
    val isValid: Boolean
)

