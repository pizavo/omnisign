package cz.pizavo.omnisign.api.model

import kotlinx.serialization.Serializable

/**
 * Metadata returned alongside an extended PDF binary.
 *
 * @property newLevel The PAdES level after extension.
 * @property warnings Human-readable warning summaries.
 */
@Serializable
data class ExtensionResultMeta(
	val newLevel: String,
	val warnings: List<String> = emptyList(),
)

