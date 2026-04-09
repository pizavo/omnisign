package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response body returned by `GET /auth/callback/{provider}` on a successful login.
 *
 * Clients should store [token] and include it as `Authorization: Bearer <token>` on
 * further API requests.
 *
 * @property token Signed JWT session token.
 * @property expiresIn Token lifetime in seconds from the time of issue.
 * @property user Identity of the authenticated user.
 */
@Serializable
data class TokenResponse(
    val token: String,
    val expiresIn: Long,
    val user: SessionResponse,
)

