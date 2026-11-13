package cz.pizavo.omnisign.auth

/**
 * Outcome of a successful [IdTokenVerifier.verify] call.
 *
 * Only the fields needed by the OIDC callback handler are exposed here. The full set of
 * id_token claims is intentionally not propagated: UserInfo remains the source of rich
 * identity claims such as those [areRequiredClaimsSatisfied] inspects. The id_token
 * contributes the cryptographic `sub` binding, and the address the IdP signed together with
 * whether it vouches for that address, which [withIdToken] prefers to UserInfo's.
 *
 * @property subject The id_token `sub` claim. Cross-checked against the UserInfo
 *   `sub` claim in the route handler so any UserInfo substitution is detected.
 * @property email The id_token `email` claim, if present. Optional in OIDC; many
 *   providers only emit it via UserInfo.
 * @property issuer The id_token `iss` claim (already verified to equal the
 *   discovery document's issuer). Echoed back to the caller for logging.
 * @property emailVerified Whether the token marks [email] verified: its `email_verified`
 *   claim and, for a [cz.pizavo.omnisign.config.SsoProviderPreset.MICROSOFT] provider, its
 *   optional `xms_edov` claim, which says the address's domain is verified in the user's
 *   tenant. `false` when either says no, `true` when one says yes and none says no, and
 *   `null` when the token carries neither.
 */
data class VerifiedIdToken(
    val subject: String,
    val email: String?,
    val issuer: String,
    val emailVerified: Boolean? = null,
)
