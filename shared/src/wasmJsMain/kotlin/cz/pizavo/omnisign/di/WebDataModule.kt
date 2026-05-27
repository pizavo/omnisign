package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.remote.RemoteCapabilitiesRepository
import cz.pizavo.omnisign.data.remote.RemoteValidationRepository
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module wiring the web target's data layer.
 *
 * Builds a single Ktor [HttpClient] with kotlinx-serialization content
 * negotiation installed and a default request URL anchored at [serverBaseUrl],
 * then binds each remote-backed repository implementation against its
 * platform-agnostic interface declared in `shared/commonMain`. `expectSuccess`
 * is enabled so non-2xx HTTP responses surface as `ResponseException` instead
 * of being silently coerced through Ktor's response transformers; the
 * `Remote*Repository` impls catch those and map them to a domain
 * `OperationError`.
 *
 * Currently wires [CapabilitiesRepository] and [ValidationRepository]; further
 * `Remote*Repository` bindings (signing, timestamp, configuration) will be
 * added as those features land on the web target.
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
            expectSuccess = true
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
    single<ValidationRepository> { RemoteValidationRepository(get()) }
}
