package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response body returned by `GET /auth/callback/{provider}` and `POST /auth/refresh` on
 * a successful login or refresh.
 *
 * Clients should:
 * - Store [token] in memory and include it as `Authorization: Bearer <token>` on further
 *   API requests.
 * - Store [refreshToken] in a secure location (HTTP-only cookie or `localStorage`) and
 *   POST it as the body of `/auth/refresh` shortly before [token] expires. Each refresh
 *   rotates the refresh token (the old one is invalidated atomically) so a stolen
 *   refresh token only works until the legitimate user next refreshes.
 *
 * @property token Signed JWT access token. Short-lived — the server's
 *   `auth.session.tokenExpirySeconds`, 5 minutes by default; use [refreshToken] to mint a
 *   fresh one. Read [expiresIn] rather than assuming the default.
 * @property refreshToken Opaque, server-side-stored refresh token. Long-lived — the
 *   server's `auth.session.refreshTokenLifetimeSeconds`, 30 days by default, though
 *   `auth.session.maxSessionSeconds` caps the session as a whole regardless. Sent only to
 *   `/auth/refresh` and `/auth/logout`.
 * @property expiresIn Access-token lifetime in seconds from the time of issue.
 * @property user Identity of the authenticated user.
 */
@Serializable
data class TokenResponse(
    val token: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: SessionResponse,
)

