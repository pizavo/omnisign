package cz.pizavo.omnisign.auth

/**
 * Determines whether the IdP's word on a user's address satisfies the operator's
 * `allowedEmailDomains` policy, once [isEmailDomainAllowed] has admitted the address itself.
 *
 * A concrete domain list is only as strong as the IdP's control of the addresses it hands
 * out. An IdP that lets users claim an address without proving they own it (self-registration
 * with email verification off, a database connection that allows login before verification,
 * or another Entra tenant whose administrator sets any address) would otherwise admit an
 * outsider as `director@contoso.com`. So under a concrete list:
 * - an address the IdP marks verified is admitted;
 * - an address the IdP marks unverified is refused, whatever [assumeEmailVerified] says;
 * - an address the IdP says nothing about is admitted only when the operator set
 *   [assumeEmailVerified] for the provider, vouching that every address it hands out is
 *   trustworthy although it sends no flag.
 *
 * The wildcard entry `"*"` admits every authenticated user, as in [isEmailDomainAllowed], so
 * verification is not consulted there.
 *
 * @param emailVerified Whether the IdP marks the address verified, or `null` when it says
 *   nothing about it (see [OidcAuthResult.emailVerified]).
 * @param allowedDomains The provider's non-empty `allowedEmailDomains` list.
 * @param assumeEmailVerified The provider's
 *   [assumeEmailVerified][cz.pizavo.omnisign.config.OidcProviderConfig.assumeEmailVerified]
 *   setting.
 * @return `true` when the login may proceed on this address.
 */
internal fun isEmailVerificationAccepted(
    emailVerified: Boolean?,
    allowedDomains: List<String>,
    assumeEmailVerified: Boolean,
): Boolean = when {
    "*" in allowedDomains -> true
    emailVerified != null -> emailVerified
    else -> assumeEmailVerified
}
