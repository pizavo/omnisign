package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response for `GET /api/v1/capabilities` describing which operations and
 * configuration profiles the server exposes.
 *
 * The web frontend uses this to dynamically show or hide UI features, including
 * whether to present the SSO login prompt ([authEnabled]).
 *
 * @property allowedOperations Names of the enabled [cz.pizavo.omnisign.config.AllowedOperation] entries.
 * @property profiles Names of the available configuration profiles.
 * @property maxFileSize Maximum upload file size in bytes.
 * @property authEnabled Whether the server requires JWT authentication ([cz.pizavo.omnisign.config.AuthConfig.enabled]).
 *   The frontend should redirect unauthenticated users to `/auth/login` when this is `true`.
 */
@Serializable
data class CapabilitiesResponse(
	val allowedOperations: List<String>,
	val profiles: List<String>,
	val maxFileSize: Long,
	val authEnabled: Boolean,
)

