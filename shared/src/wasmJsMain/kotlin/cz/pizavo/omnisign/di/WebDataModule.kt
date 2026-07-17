package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.remote.RemoteArchivingRepository
import cz.pizavo.omnisign.data.remote.RemoteCapabilitiesRepository
import cz.pizavo.omnisign.data.remote.RemoteConfigArchive
import cz.pizavo.omnisign.data.remote.RemoteConfigRepository
import cz.pizavo.omnisign.data.remote.RemoteSigningRepository
import cz.pizavo.omnisign.data.remote.RemoteTrustStore
import cz.pizavo.omnisign.data.remote.RemoteValidationRepository
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.web.auth.WebAuthApi
import cz.pizavo.omnisign.web.auth.WebAuthState
import cz.pizavo.omnisign.web.auth.authRefreshPlugin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module wiring the web target's data layer.
 *
 * Builds **two** Ktor [HttpClient]s, both anchored at [serverBaseUrl] with kotlinx-serialization
 * content negotiation:
 *
 * - The default (unqualified) **API client** carries the session: it attaches the [WebAuthState]
 *   access token as `Authorization: Bearer …` on every request, and has `expectSuccess` enabled so
 *   a non-2xx surfaces as `ResponseException` for the `Remote*Repository` impls to map to a domain
 *   `OperationError`. Every repository binds to this client.
 * - The [AUTH_HTTP_CLIENT]-qualified **bare client** backs [WebAuthApi] and nothing else. It sends
 *   no bearer token and has `expectSuccess` off, because the `/auth` bootstrap calls must not carry
 *   a token (they exist to obtain one) and a `401` from them is an expected value to read, not an
 *   exception — see [WebAuthApi] for why routing them through the API client would recurse.
 *
 * Wires [CapabilitiesRepository], [ValidationRepository], [ConfigRepository],
 * [SigningRepository], and [ArchivingRepository] against their remote-backed
 * implementations, plus a read-only [cz.pizavo.omnisign.domain.repository.TrustStore]
 * ([RemoteTrustStore]) so the trusted-certificate panels show exactly the trust the
 * server validates with, and [WebAuthState] + [WebAuthApi] for the login flow.
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
    single { WebAuthState() }

    single(named(AUTH_HTTP_CLIENT)) {
        HttpClient {
            expectSuccess = false
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

    single { WebAuthApi(get(named(AUTH_HTTP_CLIENT)), get()) }

    single {
        val authState: WebAuthState = get()
        val authApi: WebAuthApi = get()
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
            install(authRefreshPlugin(authState, authApi))
            defaultRequest {
                if (serverBaseUrl.isNotBlank()) url(serverBaseUrl)
                languageProvider()?.takeIf { it.isNotBlank() }
                    ?.let { headers.append(HttpHeaders.AcceptLanguage, it) }
                authState.accessToken?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    }
    single<CapabilitiesRepository> { RemoteCapabilitiesRepository(get()) }
    single<ValidationRepository> { RemoteValidationRepository(get()) }
    single<ConfigRepository> { RemoteConfigRepository(get(), get()) }
    single<SigningRepository> { RemoteSigningRepository(get()) }
    single<ArchivingRepository> { RemoteArchivingRepository(get()) }
    single<TrustStore> { RemoteTrustStore(get()) }
    single<ConfigArchivePort> { RemoteConfigArchive(get()) }
}

/**
 * Koin qualifier for the bare [HttpClient] that backs [WebAuthApi] — the one with no bearer token
 * and `expectSuccess` off, kept distinct from the session-carrying API client the repositories use.
 */
const val AUTH_HTTP_CLIENT: String = "authHttpClient"
