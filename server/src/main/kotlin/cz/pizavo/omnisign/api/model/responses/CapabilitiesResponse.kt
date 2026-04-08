package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response for `GET /api/v1/capabilities` describing which operations and
 * configuration profiles the server exposes.
 *
 * The web frontend uses this to dynamically show or hide UI features.
 *
 * @property allowedOperations Names of the enabled [cz.pizavo.omnisign.config.AllowedOperation] entries.
 * @property profiles Names of the available configuration profiles.
 * @property maxFileSize Maximum upload file size in bytes.
 */
@Serializable
data class CapabilitiesResponse(
	val allowedOperations: List<String>,
	val profiles: List<String>,
	val maxFileSize: Long,
)

