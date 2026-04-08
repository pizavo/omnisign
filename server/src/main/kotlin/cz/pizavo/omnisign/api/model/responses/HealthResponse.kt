package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Health check response.
 *
 * @property status Always `"ok"` when the server is running.
 * @property version Application version string.
 */
@Serializable
data class HealthResponse(
	val status: String = "ok",
	val version: String,
)

