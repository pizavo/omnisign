package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response for `GET /api/v1/capabilities` describing which operations and
 * configuration profiles the server exposes.
 *
 * A web frontend uses this to dynamically show or hide UI features, including
 * whether to present the SSO login prompt ([authEnabled]).
 *
 * Lives in `shared/commonMain` so the server (producing the response) and any
 * client (deserializing it) reference the exact same type. No platform-specific
 * dependencies — pure data carrier.
 *
 * @property allowedOperations Names of the enabled `AllowedOperation` entries
 *   on the server (e.g. `"VALIDATE"`, `"TIMESTAMP"`, `"SIGN"`).
 * @property profiles Names of the available configuration profiles on the
 *   server. Empty when the caller is unauthenticated and the server has auth
 *   enabled (to avoid leaking internal profile names).
 * @property maxFileSize Maximum upload file size in bytes.
 * @property authEnabled Whether the server requires JWT authentication. The
 *   frontend should redirect unauthenticated users to `/auth/login` when this
 *   is `true`.
 */
@Serializable
data class CapabilitiesResponse(
    val allowedOperations: List<String>,
    val profiles: List<String>,
    val maxFileSize: Long,
    val authEnabled: Boolean,
)
