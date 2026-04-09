package cz.pizavo.omnisign.auth

import kotlinx.serialization.json.JsonObject

/**
 * Result of a successful OIDC user-info resolution, combining the mapped
 * [AuthenticatedPrincipal] with the full set of raw IdP claims.
 *
 * The raw [claims] are retained so that post-login filters such as
 * [areRequiredClaimsSatisfied] can inspect IdP-specific attributes that are not
 * mapped onto [AuthenticatedPrincipal], for example `schac_home_organization` or
 * `eduperson_scoped_affiliation`.
 *
 * @property principal Mapped principal ready for JWT issuance.
 * @property claims Full raw claims object from the OIDC `/userinfo` endpoint.
 */
data class OidcAuthResult(
    val principal: AuthenticatedPrincipal,
    val claims: JsonObject,
)

