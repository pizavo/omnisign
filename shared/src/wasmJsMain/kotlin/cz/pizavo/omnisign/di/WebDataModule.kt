package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.remote.RemoteArchivingRepository
import cz.pizavo.omnisign.data.remote.RemoteCapabilitiesRepository
import cz.pizavo.omnisign.data.remote.RemoteConfigRepository
import cz.pizavo.omnisign.data.remote.RemoteSigningRepository
import cz.pizavo.omnisign.data.remote.RemoteValidationRepository
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
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
 * Wires [CapabilitiesRepository], [ValidationRepository], [ConfigRepository],
 * [SigningRepository], and [ArchivingRepository] against their remote-backed
 * implementations.
 *
 * @param serverBaseUrl Origin of the OmniSign server (e.g.
 *   `"https://omnisign.example.com"`). All HTTP requests are issued relative
 *   to this URL. When empty, the Ktor client treats requests as relative to
 *   the browser's current origin — the same-origin deployment topology where
 *   the server hosts both the web bundle and the API.
 * @param languageProvider Supplies the UI language tag advertised on every request via the standard
 *   `Accept-Language` header, evaluated per request so it tracks the current preference. Returns
 *   `null` (the default) to send no header, in which case the server localizes to its own default
 *   locale — this is what lets the server return the DSS validation report in the user's language.
 */
fun webDataModule(serverBaseUrl: String, languageProvider: () -> String? = { null }): Module = module {
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
            defaultRequest {
                if (serverBaseUrl.isNotBlank()) url(serverBaseUrl)
                languageProvider()?.takeIf { it.isNotBlank() }
                    ?.let { headers.append(HttpHeaders.AcceptLanguage, it) }
            }
        }
    }
    single<CapabilitiesRepository> { RemoteCapabilitiesRepository(get()) }
    single<ValidationRepository> { RemoteValidationRepository(get()) }
    single<ConfigRepository> { RemoteConfigRepository(get(), get()) }
    single<SigningRepository> { RemoteSigningRepository(get()) }
    single<ArchivingRepository> { RemoteArchivingRepository(get()) }
}
