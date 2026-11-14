package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.config.OidcProviderConfig

/**
 * Refuse [OidcProviderConfig.assumeEmailVerified] on a provider whose discovery document is one
 * of Microsoft Entra ID's multi-tenant ones.
 *
 * Through Entra ID's `common` and `organizations` endpoints any Entra tenant can sign in, and
 * each tenant's administrator can give a user any address, the operator's own domain included.
 * Entra sends no `email_verified` claim, so assuming its addresses verified would let a
 * stranger's tenant pass the domain list; the optional `xms_edov` claim is the way to admit
 * these users.
 *
 * The document is judged, not the URL it came from. Every multi-tenant Entra document, in every
 * cloud and on every alias of its sign-in host, names an issuer with the `{tenantid}`
 * placeholder, such as `https://login.microsoftonline.com/{tenantid}/v2.0`, and no single-tenant
 * one does. A URL can reach those endpoints in more spellings than a check could list: Microsoft
 * resolves `..` segments and decodes the path more than once. The placeholder is matched ignoring
 * case, so a differently spelled one fails closed. The server fetches each provider's document at
 * startup, so the refusal stops the boot.
 *
 * @param provider The provider being registered.
 * @param discovery Its discovery document.
 * @throws IllegalArgumentException naming the provider, the issuer and the remedy when
 *   [provider] assumes verification and [discovery] names such an issuer.
 */
internal fun requireAssumableEmailVerification(provider: OidcProviderConfig, discovery: OidcDiscoveryDocument) {
    val multiTenant = discovery.issuer.contains(TENANT_ID_PLACEHOLDER, ignoreCase = true)
    require(!(provider.assumeEmailVerified && multiTenant)) {
        "provider '${provider.name}' sets assumeEmailVerified, but its discovery document is one of " +
            "Microsoft Entra ID's multi-tenant ones (issuer '${discovery.issuer}'), where any tenant's " +
            "administrator can give a user any address. Remove it, use preset MICROSOFT, and add the " +
            "optional claim xms_edov to the app registration's ID token, which says whether the " +
            "address's domain is verified in the user's tenant."
    }
}

/** The placeholder Entra ID's multi-tenant discovery documents put in the issuer for the user's tenant. */
private const val TENANT_ID_PLACEHOLDER = "{tenantid}"
