package cz.pizavo.omnisign.web

/**
 * Outcome of resolving the web target's runtime configuration from an optional `web-config.json`:
 * the effective server base URL plus any deploy-time branding.
 *
 * @property url The effective base URL to use — the config's `serverUrl` when set
 *   and non-blank, otherwise the build-time fallback.
 * @property malformedConfig True when a non-blank `web-config.json` was found but
 *   could not be parsed; [url] then holds the build-time fallback and the caller
 *   may surface a diagnostic.
 * @property organizationName The provider branding label from `web-config.json`, or `null` when
 *   unset/blank/absent. Drives the `"<name> · OmniSign"` title and the `powered by OmniSign` mark.
 */
data class ResolvedServerUrl(
    val url: String,
    val malformedConfig: Boolean,
    val organizationName: String? = null,
)
