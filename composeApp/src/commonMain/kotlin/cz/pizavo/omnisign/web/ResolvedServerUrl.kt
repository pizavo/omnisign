package cz.pizavo.omnisign.web

/**
 * Outcome of resolving the web target's server base URL from an optional
 * `web-config.json`.
 *
 * @property url The effective base URL to use — the config's `serverUrl` when set
 *   and non-blank, otherwise the build-time fallback.
 * @property malformedConfig True when a non-blank `web-config.json` was found but
 *   could not be parsed; [url] then holds the build-time fallback and the caller
 *   may surface a diagnostic.
 */
data class ResolvedServerUrl(
    val url: String,
    val malformedConfig: Boolean,
)
