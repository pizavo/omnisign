package cz.pizavo.omnisign.auth

import io.ktor.server.auth.*
import kotlin.time.Instant

/**
 * Ktor [Principal] representing an authenticated OmniSign user.
 *
 * Populated after a successful SSO login (OIDC callback or header injection) and
 * embedded into the JWT session token as claims.
 *
 * @property userId Stable unique identifier from the identity provider (e.g., OIDC `sub` claim).
 *   This is the load-bearing identity field — refresh-token lookup, JWT subject, and the
 *   M-3 id_token cross-check all pivot on it.
 * @property email User's e-mail address as reported by the IdP, or `null` when the IdP
 *   does not supply one (e.g., GitHub users with private email visibility, or a
 *   Shibboleth SP that does not inject the email attribute). Downstream consumers
 *   (`isEmailDomainAllowed`, JWT `email` claim, log lines) must handle the `null`
 *   case explicitly — see [isEmailDomainAllowed] for the M-6 filter policy under
 *   missing email.
 * @property displayName Human-readable full name, or `null` if not provided by the IdP.
 * @property providerName Name of the [cz.pizavo.omnisign.config.SsoProviderConfig] that
 *   authenticated this user (matches [cz.pizavo.omnisign.config.SsoProviderConfig.name]).
 * @property authTime Instant at which the underlying SSO authentication completed. Set when
 *   the principal is first minted at `/auth/callback/{name}` and **preserved verbatim across
 *   all subsequent refreshes** — never reset to "now" on `/auth/refresh`. The refresh route
 *   compares `now - authTime` against
 *   [cz.pizavo.omnisign.config.SessionConfig.maxSessionSeconds] to bound the absolute
 *   session lifetime regardless of how many refreshes have happened. Carried in the JWT
 *   as the standard `auth_time` claim (RFC 7519 §5; OIDC Core §2).
 */
data class AuthenticatedPrincipal(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val providerName: String,
    val authTime: Instant,
)

