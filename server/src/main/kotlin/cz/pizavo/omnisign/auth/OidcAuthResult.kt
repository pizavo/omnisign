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
 * @property emailVerified Whether the IdP marks the principal's
 *   [email][AuthenticatedPrincipal.email] verified, or `null` when it says nothing about it.
 *   [isEmailVerificationAccepted] decides what each answer means for the login.
 */
data class OidcAuthResult(
    val principal: AuthenticatedPrincipal,
    val claims: JsonObject,
    val emailVerified: Boolean? = null,
)

/**
 * Fold what the verified [idToken] says about the user's address into this result.
 *
 * The id_token is signed by the IdP, so the address it names takes precedence over the
 * UserInfo one and becomes the principal's. Its verification flag counts only together with
 * that address, and the UserInfo flag only while UserInfo named the same address, ignoring
 * case. Of the flags that count, one saying the address is not verified wins, so neither
 * source can vouch for more than the other allows.
 *
 * @param idToken The verified id_token of the login, or `null` when the provider's token was
 *   not verified; the result is then returned unchanged.
 * @return The result to judge the login by and mint its session from.
 */
fun OidcAuthResult.withIdToken(idToken: VerifiedIdToken?): OidcAuthResult {
    val signedEmail = idToken?.email ?: return this
    val userInfoFlag = emailVerified.takeIf { principal.email.equals(signedEmail, ignoreCase = true) }
    return copy(
        principal = principal.copy(email = signedEmail),
        emailVerified = listOfNotNull(idToken.emailVerified, userInfoFlag).minOrNull(),
    )
}
