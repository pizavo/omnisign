package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response body returned by `GET /auth/session` when the caller has a valid session.
 *
 * @property userId Stable unique identifier from the identity provider.
 * @property email Authenticated user's e-mail address, or `null` when the IdP does not
 *   supply one (e.g., a GitHub user with private email visibility, or a Shibboleth SP
 *   that does not inject the email attribute).
 * @property displayName Human-readable full name, or `null` if not provided by the IdP.
 * @property providerName Name of the SSO provider that authenticated this user.
 */
@Serializable
data class SessionResponse(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val providerName: String,
)

