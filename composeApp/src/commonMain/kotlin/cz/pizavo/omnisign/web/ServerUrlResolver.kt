package cz.pizavo.omnisign.web

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val webConfigJson = Json { ignoreUnknownKeys = true }

/**
 * Resolves the effective OmniSign server base URL from optional raw
 * `web-config.json` text, independent of how that text was obtained.
 *
 * Precedence: a non-blank [WebRuntimeConfig.serverUrl] parsed from [configText]
 * wins; otherwise [buildTimeDefault] is used (an empty default meaning "same
 * origin"). [configText] that is null or blank is treated as "no runtime config".
 * Text that is present but unparseable falls back to [buildTimeDefault] and is
 * flagged via [ResolvedServerUrl.malformedConfig] so the caller can warn.
 *
 * @param configText Raw `web-config.json` body, or null when no file was found.
 * @param buildTimeDefault Server URL baked at build time (from
 *   [cz.pizavo.omnisign.BuildConfig.SERVER_URL]).
 * @return The resolved URL together with whether the config was malformed.
 */
fun resolveServerUrl(configText: String?, buildTimeDefault: String): ResolvedServerUrl {
    if (configText.isNullOrBlank()) {
        return ResolvedServerUrl(buildTimeDefault, malformedConfig = false)
    }
    return try {
        val config = webConfigJson.decodeFromString<WebRuntimeConfig>(configText)
        val url = config.serverUrl?.takeIf { it.isNotBlank() } ?: buildTimeDefault
        ResolvedServerUrl(
            url = url,
            malformedConfig = false,
            organizationName = config.organizationName?.takeIf { it.isNotBlank() },
        )
    } catch (_: SerializationException) {
        ResolvedServerUrl(buildTimeDefault, malformedConfig = true)
    }
}
