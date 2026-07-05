@file:OptIn(ExperimentalWasmJsInterop::class)

package cz.pizavo.omnisign.web

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val WEB_CONFIG_PATH = "web-config.json"

private const val CONFIG_FETCH_TIMEOUT_MS = 3000L

/**
 * Fetches [path] relative to the document origin and resolves to the response
 * body text, or an empty string for any non-2xx response.
 *
 * Implemented as a raw `fetch` so this bootstrap step stays independent of the
 * Ktor [io.ktor.client.HttpClient] built by [cz.pizavo.omnisign.di.webDataModule],
 * whose base URL is exactly what the resolver is trying to determine.
 */
private fun fetchWebConfigText(path: String): Promise<JsString> =
    js("fetch(path).then(function (response) { return response.ok ? response.text() : ''; })")

/** Logs [message] to the browser console at warning level. */
private fun consoleWarn(message: String): Unit =
    js("console.warn(message)")

/**
 * Resolves the OmniSign server base URL for the web target at runtime by fetching
 * an optional `web-config.json` served next to the bundle and delegating the
 * decision to [resolveServerUrl].
 *
 * Lets an operator retarget a pre-built bundle without recompiling. Falls back to
 * [buildTimeDefault] when the file is missing, blank, malformed, or unreachable
 * within [CONFIG_FETCH_TIMEOUT_MS]; an empty fallback is treated as "same origin"
 * by [cz.pizavo.omnisign.di.webDataModule]. A present-but-unparseable file is
 * reported via `console.warn` so a deployment typo is not silently ignored.
 *
 * @param buildTimeDefault Server URL baked at build time (from
 *   [cz.pizavo.omnisign.BuildConfig.SERVER_URL]).
 * @return The server base URL to hand to [cz.pizavo.omnisign.di.webDataModule].
 */
suspend fun resolveServerBaseUrl(buildTimeDefault: String): String {
    val configText: String? = try {
        withTimeoutOrNull(CONFIG_FETCH_TIMEOUT_MS.milliseconds) {
            val response: JsString = fetchWebConfigText(WEB_CONFIG_PATH).await()
            response.toString()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
    val resolution = resolveServerUrl(configText, buildTimeDefault)
    if (resolution.malformedConfig) {
        consoleWarn("OmniSign: $WEB_CONFIG_PATH is present but could not be parsed; using the built-in server URL.")
    }
    return resolution.url
}
