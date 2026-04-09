package cz.pizavo.omnisign.auth

import io.ktor.server.auth.*

/**
 * Ktor [Principal] representing an authenticated OmniSign user.
 *
 * Populated after a successful SSO login (OIDC callback or header injection) and
 * embedded into the JWT session token as claims.
 *
 * @property userId Stable unique identifier from the identity provider (e.g., OIDC `sub` claim).
 * @property email User's e-mail address as reported by the IdP.
 * @property displayName Human-readable full name, or `null` if not provided by the IdP.
 * @property providerName Name of the [cz.pizavo.omnisign.config.SsoProviderConfig] that
 *   authenticated this user (matches [cz.pizavo.omnisign.config.SsoProviderConfig.name]).
 */
data class AuthenticatedPrincipal(
    val userId: String,
    val email: String,
    val displayName: String?,
    val providerName: String,
)

