package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.remote.RemoteCapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module wiring the web target's data layer.
 *
 * Builds a single Ktor [HttpClient] backed by the `Js` engine — the
 * fetch-based engine Ktor publishes for the JavaScript and `wasmJs` targets,
 * which works in browser hosts (the CIO engine is also offered for `wasmJs`
 * but relies on Node's `net` module and therefore only runs under Node-Wasm).
 * Content negotiation is installed with kotlinx-serialization, and a default
 * request URL is anchored at [serverBaseUrl]. Binds each remote-backed repository
 * implementation against its platform-agnostic interface declared in
 * `shared/commonMain`.
 *
 * Currently only [CapabilitiesRepository] is wired; further `Remote*Repository`
 * bindings (validation, signing, timestamp, configuration) will be added to
 * this module as those features land on the web target.
 *
 * @param serverBaseUrl Origin of the OmniSign server (e.g.
 *   `"https://omnisign.example.com"`). All HTTP requests are issued relative
 *   to this URL. When empty, the Ktor client treats requests as relative to
 *   the browser's current origin — the same-origin deployment topology where
 *   the server hosts both the web bundle and the API.
 */
fun webDataModule(serverBaseUrl: String): Module = module {
    single {
        HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = false
                    },
                )
            }
            if (serverBaseUrl.isNotBlank()) {
                defaultRequest {
                    url(serverBaseUrl)
                }
            }
        }
    }
    single<CapabilitiesRepository> { RemoteCapabilitiesRepository(get()) }
}
