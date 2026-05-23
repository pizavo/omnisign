package cz.pizavo.omnisign.api.model.requests

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /auth/refresh` and `POST /auth/logout`.
 *
 * Both endpoints accept an opaque refresh token in the body rather than via the
 * `Authorization` header so the JWT-bearer-token mechanism stays unambiguous:
 * - Authorization header = short-lived access token used on every API call.
 * - This body = long-lived refresh token used only for refresh and logout.
 *
 * @property refreshToken The opaque refresh token previously issued in
 *   [cz.pizavo.omnisign.api.model.responses.TokenResponse.refreshToken].
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)
