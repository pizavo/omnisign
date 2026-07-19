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
 * Called from [cz.pizavo.omnisign.moduleWith] before any provider-dependent component
 * is wired into the application module, so a misconfiguration aborts the server boot
 * with a clear `IllegalArgumentException` rather than producing an undefined runtime
 * state.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 * @throws IllegalArgumentException with a provider-named message when any OIDC provider
 *   has an empty `allowedEmailDomains` list or any `requiredClaims` entry has an empty
 *   accepted-values list, and with the offending entry when an `allowedRedirectUris` entry
 *   is not an absolute `https` URL (or an `http` one on a loopback host).
 */
fun validateAuthConfig(authConfig: AuthConfig?) {
	val config = authConfig ?: return
	config.providers.filterIsInstance<OidcProviderConfig>().forEach { provider ->
		require(provider.allowedEmailDomains.isNotEmpty()) {
			"provider '${provider.name}' must set allowedEmailDomains. " +
				"Use [\"*\"] to allow any domain, or list specific ones."
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
 * Check a single `auth.allowedRedirectUris` entry.
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
	require(scheme == "https" || isLoopbackHost(url.host)) {
		"auth.allowedRedirectUris entry '$entry' is plain http on the non-loopback host " +
			"'${url.host}'. The callback delivers a single-use hand-off code to this URL, so " +
			"over cleartext it is delivered to the network as well. Use https, or a loopback " +
			"host (127.0.0.1, ::1, localhost) for local development."
	}
}
