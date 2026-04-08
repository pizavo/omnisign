package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside a timestamped/extended PDF binary.
 *
 * @property newLevel The PAdES level after timestamping/extension.
 * @property warnings Human-readable warning summaries.
 */
@Serializable
data class TimestampResultMeta(
	val newLevel: String,
	val warnings: List<String> = emptyList(),
)

