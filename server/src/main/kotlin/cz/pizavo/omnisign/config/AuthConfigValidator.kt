package cz.pizavo.omnisign.config

/**
 * Validate operator-supplied authentication configuration at server startup.
 *
 * Catches two classes of misconfiguration that the data-class layer cannot:
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
 * Called from [cz.pizavo.omnisign.moduleWith] before any provider-dependent component
 * is wired into the application module, so a misconfiguration aborts the server boot
 * with a clear `IllegalArgumentException` rather than producing an undefined runtime
 * state. Header-injection providers carry no email/claims filter today (see
 * `HeaderInjectionProviderConfig` KDoc) so they are not validated here.
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 * @throws IllegalArgumentException with a provider-named message when any OIDC provider
 *   has an empty `allowedEmailDomains` list, or any `requiredClaims` entry has an empty
 *   accepted-values list.
 */
fun validateAuthConfig(authConfig: AuthConfig?) {
	val providers = authConfig?.providers ?: return
	providers.filterIsInstance<OidcProviderConfig>().forEach { provider ->
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
}
