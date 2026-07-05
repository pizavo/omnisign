package cz.pizavo.omnisign.web

import kotlinx.serialization.Serializable

/**
 * Optional deploy-time configuration for the OmniSign web bundle, read at startup
 * from a `web-config.json` file served next to `index.html`.
 *
 * This is the deploy-time counterpart to the build-time `OMNISIGN_SERVER_URL`
 * baked into [cz.pizavo.omnisign.BuildConfig.SERVER_URL]: it lets an operator point
 * a pre-built bundle at its OmniSign server without recompiling the Wasm. The file
 * is optional — when it is absent, or [serverUrl] is null or blank, the bundle uses
 * the build-time fallback (empty by default, meaning "same origin"). Unknown JSON
 * fields are ignored so the schema can grow compatibly.
 *
 * @property serverUrl Origin the bundle should issue API requests against (e.g.
 *   `"https://omnisign.example.com:18443"`). Null or blank selects the build-time
 *   fallback.
 */
@Serializable
data class WebRuntimeConfig(
    val serverUrl: String? = null,
)
