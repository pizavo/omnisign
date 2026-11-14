package cz.pizavo.omnisign.config

import java.net.URI

/**
 * Validate operator-supplied authentication configuration at server startup.
 *
 * Catches three classes of misconfiguration that the data-class layer cannot:
 *
 * 1. **Empty `allowedEmailDomains` on an [OidcProviderConfig].** Jackson can reject the
 *    missing-field case (since the property is non-nullable with no default) but it
 *    cannot detect an explicit `allowedEmailDomains: []` in YAML. For a signing service
 *    that distinction matters: an empty list reads as "deny everyone" to the operator
 *    but, without this check, would collapse into "allow everyone" at the deserialiser
 *    level. Both the empty and the missing case must surface with the same
 *    operator-facing message so the path of least resistance is to make a deliberate
 *    choice (`["*"]` for explicit allow-all, or a concrete list).
 *
 * 2. **Empty value list under `requiredClaims.<key>`.** A `requiredClaims` entry with
 *    no accepted values silently rejects every login on that claim — almost always a
 *    typo where the operator meant to write actual values. Surfacing it loudly at
 *    startup prevents a quiet 100%-rejection outage in production.
 *
 * 3. **Malformed `allowedRedirectUris` entries.** These are matched exactly and nothing
 *    normalises them first, so an entry that is not a complete absolute URL can only ever
 *    fail to match — a login that dead-ends at the callback rather than an error anyone can
 *    trace. Rejecting `http` for non-loopback hosts belongs here too: the entry decides
 *    where a hand-off code is delivered, and delivering one over cleartext hands it to the
 *    network. Neither is something [cz.pizavo.omnisign.auth.isRedirectUriAllowed] can
 *    reasonably second-guess at request time — by then the operator is not watching.
 *
 * 4. **`assumeEmailVerified` on a multi-tenant Microsoft provider.** With `tenantId` `common`
 *    or `organizations`, or a `discoveryUrl` on such an endpoint, any Entra tenant can sign
 *    in, and each tenant's administrator can give a user any address, the operator's own
 *    domain included. Entra sends no `email_verified` claim, so assuming its addresses
 *    verified would let a stranger's tenant pass the domain list; the optional `xms_edov`
 *    claim is the way to admit these users.
 *
 * Called from [cz.pizavo.omnisign.moduleWith] before any provider-dependent component
 * is wired into the application module, so a misconfiguration aborts the server boot
 * with a clear `IllegalArgumentException` rather than producing an undefined runtime
 * state.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 * @throws IllegalArgumentException with a provider-named message when any OIDC provider
 *   has an empty `allowedEmailDomains` list, any `requiredClaims` entry has an empty
 *   accepted-values list, or a multi-tenant Microsoft provider sets `assumeEmailVerified`,
 *   and with the offending entry when an `allowedRedirectUris` entry is not an absolute
 *   `https` URL (or an `http` one on a loopback host).
 */
fun validateAuthConfig(authConfig: AuthConfig?) {
	val config = authConfig ?: return
	config.providers.filterIsInstance<OidcProviderConfig>().forEach { provider ->
		require(provider.allowedEmailDomains.isNotEmpty()) {
			"provider '${provider.name}' must set allowedEmailDomains. " +
				"Use [\"*\"] to allow any domain, or list specific ones."
		}
		val multiTenantEndpoint = multiTenantMicrosoftEndpoint(provider)
		require(!(provider.assumeEmailVerified && multiTenantEndpoint != null)) {
			"provider '${provider.name}' sets assumeEmailVerified on the multi-tenant Microsoft " +
				"endpoint '$multiTenantEndpoint', where any tenant's administrator can give a user any " +
				"address. Remove it, use preset MICROSOFT, and add the optional claim xms_edov to the " +
				"app registration's ID token, which says whether the address's domain is verified in " +
				"the user's tenant."
		}
		provider.requiredClaims?.forEach { (key, values) ->
			require(values.isNotEmpty()) {
				"provider '${provider.name}' has requiredClaims.$key with no accepted values — " +
					"all logins would be rejected. Either list values or remove the key."
			}
		}
	}
	config.allowedRedirectUris.forEach(::validateRedirectUri)
}

/**
 * The multi-tenant Microsoft endpoint [provider] signs users in through, if any.
 *
 * The discovery URL decides, as it does for [cz.pizavo.omnisign.auth.OidcDiscoveryService]:
 * an explicit `discoveryUrl` wins over the preset's template filled with `tenantId`. Such a
 * URL counts as Microsoft's when the provider uses [SsoProviderPreset.MICROSOFT] or the host is
 * one of Entra ID's sign-in hosts, which covers the national clouds that only an explicit URL
 * reaches.
 *
 * @return The `tenantId` or `discoveryUrl` that makes the provider multi-tenant, or `null`
 *   when it is not.
 */
private fun multiTenantMicrosoftEndpoint(provider: OidcProviderConfig): String? {
	val discoveryUrl = provider.discoveryUrl
	if (discoveryUrl == null) {
		val multiTenant = provider.preset == SsoProviderPreset.MICROSOFT &&
			provider.tenantId?.lowercase() in MULTI_TENANT_MICROSOFT_TENANTS
		return provider.tenantId.takeIf { multiTenant }
	}
	val url = runCatching { URI(discoveryUrl) }.getOrNull() ?: return null
	val microsoft = provider.preset == SsoProviderPreset.MICROSOFT ||
		url.host?.lowercase() in MICROSOFT_SIGN_IN_HOSTS
	val tenant = url.path.orEmpty().trimStart('/').substringBefore('/').lowercase()
	return discoveryUrl.takeIf { microsoft && tenant in MULTI_TENANT_MICROSOFT_TENANTS }
}

/**
 * The tenants in a Microsoft sign-in URL (`tenantId`, or a discovery URL's first path segment)
 * that admit users of any Entra tenant, in lower case.
 */
private val MULTI_TENANT_MICROSOFT_TENANTS = setOf("common", "organizations")

/**
 * Entra ID's sign-in hosts in the global and national clouds, with their aliases, in lower
 * case.
 */
private val MICROSOFT_SIGN_IN_HOSTS = setOf(
	"login.microsoftonline.com",
	"login.microsoft.com",
	"login.windows.net",
	"sts.windows.net",
	"login.microsoftonline.us",
	"login.usgovcloudapi.net",
	"login-us.microsoftonline.com",
	"login.partner.microsoftonline.cn",
	"login.chinacloudapi.cn",
	"login.microsoftonline.de",
)

/**
 * Check a single `auth.allowedRedirectUris` entry.
 *
 * The host is compared as [isLoopbackHost] expects it: [URI.getHost] keeps an IPv6 literal's
 * brackets and the case it was written in, so both are normalised first.
 *
 * @param entry The configured value.
 * @throws IllegalArgumentException naming [entry] and what is wrong with it.
 */
private fun validateRedirectUri(entry: String) {
	val url = runCatching { URI(entry) }.getOrNull()
	require(url != null && url.isAbsolute && url.host != null) {
		"auth.allowedRedirectUris entry '$entry' is not an absolute URL. Write the page the " +
			"web app is served from in full, scheme and host included — e.g. " +
			"\"https://omnisign.example.com/\". Entries are matched exactly, so a partial " +
			"value can never match and the login would fail at the callback."
	}
	val scheme = url.scheme.lowercase()
	require(scheme == "https" || scheme == "http") {
		"auth.allowedRedirectUris entry '$entry' uses the scheme '$scheme'. Only https (or " +
			"http on a loopback host, for development) can receive a hand-off code."
	}
	require(scheme == "https" || isLoopbackHost(url.host.removeSurrounding("[", "]").lowercase())) {
		"auth.allowedRedirectUris entry '$entry' is plain http on the non-loopback host " +
			"'${url.host}'. The callback delivers a single-use hand-off code to this URL, so " +
			"over cleartext it is delivered to the network as well. Use https, or a loopback " +
			"host (127.0.0.1, ::1, localhost) for local development."
	}
}
