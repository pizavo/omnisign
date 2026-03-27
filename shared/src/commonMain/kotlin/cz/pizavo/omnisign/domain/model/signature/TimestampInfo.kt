package cz.pizavo.omnisign.domain.model.signature

import kotlin.time.Instant

/**
 * Timestamp information.
 *
 * @property type Human-readable timestamp type.
 * @property productionTime Point in time at which the timestamp was produced.
 * @property isValid Whether the timestamp passed validation.
 */
data class TimestampInfo(
    val type: String,
    val productionTime: Instant,
    val isValid: Boolean
)
