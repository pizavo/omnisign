package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.webDataModule
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.ui.branding.brandedTitle
import cz.pizavo.omnisign.ui.platform.LocalStorageProfileSelectionStore
import cz.pizavo.omnisign.ui.platform.MuPdfShim
import cz.pizavo.omnisign.ui.platform.applyWebLocale
import cz.pizavo.omnisign.ui.platform.loadUiPreferences
import cz.pizavo.omnisign.web.auth.LoginScreen
import cz.pizavo.omnisign.web.auth.SessionOutcome
import cz.pizavo.omnisign.web.auth.WebAuthApi
import cz.pizavo.omnisign.web.auth.establishSession
import cz.pizavo.omnisign.web.resolveWebRuntimeConfig
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * Web (Wasm) entry point for the OmniSign Compose Multiplatform UI.
 *
 * Boot sequence:
 *  1. Touch [MuPdfShim.init] so the MuPDF WebAssembly module's top-level await
 *     resolves before any later sync `getPdfPageCount` / `rememberPdfPageBitmap`
 *     call from the file picker.
 *  2. Resolve the server base URL via [resolveServerBaseUrl], which reads an
 *     optional deploy-time `web-config.json` served next to the bundle and falls
 *     back to the build-time [BuildConfig.SERVER_URL] (empty by default, meaning
 *     "same origin"). The lookup is asynchronous, so the rest of the boot runs in
 *     a coroutine once the URL resolves.
 *  3. Start Koin with the platform-agnostic [appModule] (use cases) plus the
 *     web-specific [webDataModule] (Ktor [io.ktor.client.HttpClient] and the
 *     `Remote*Repository` impls), anchored at the resolved server URL and given a
 *     language provider so every request advertises the UI language (persisted preference,
 *     else the browser locale) via `Accept-Language` — letting the server localize the DSS
 *     validation report to the user's language.
 *  4. Seed the runtime UI-language override from the persisted preference (via [applyWebLocale] and
 *     the `index.html` navigator shim) so the first render already uses the chosen language rather
 *     than the browser locale.
 *  5. Gate on authentication. Read the server capabilities once; when it reports `authEnabled`,
 *     [cz.pizavo.omnisign.web.auth.establishSession] resolves a session — redeeming a hand-off code
 *     on return from the identity provider, or resuming from the refresh cookie — before anything
 *     renders. With a session (or with auth disabled) the [App] mounts; otherwise the
 *     [cz.pizavo.omnisign.web.auth.LoginScreen] does. This is deliberately all-or-nothing: a server
 *     that requires auth shows nothing operational until the user is signed in. Once [App] mounts,
 *     [cz.pizavo.omnisign.ui.viewmodel.CapabilitiesViewModel] re-fetches capabilities to narrow the
 *     visible affordances (hiding Sign / Timestamp or the validation panel) to what the server
 *     allows and to pick up the now-authenticated profile list.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MuPdfShim.init()
    val webPlatformModule = module {
        single<BrowserProfileSelectionStore> { LocalStorageProfileSelectionStore() }
    }
    CoroutineScope(Dispatchers.Default).launch {
        val runtimeConfig = resolveWebRuntimeConfig(BuildConfig.SERVER_URL)
        document.title = brandedTitle(runtimeConfig.organizationName, serverOrganizationName = null)
        startKoin {
            modules(
                appModule,
                webDataModule(runtimeConfig.url) {
                    loadUiPreferences().languageTag ?: window.navigator.language.takeIf { it.isNotBlank() }
                },
                webPlatformModule,
            )
        }
        applyWebLocale(loadUiPreferences().languageTag)

        val koin = KoinPlatform.getKoin()
        val authEnabled = runCatching { koin.get<CapabilitiesRepository>().get().authEnabled }.getOrDefault(false)
        val session = if (authEnabled) establishSession(koin.get<WebAuthApi>()) else SessionOutcome(authenticated = true)

        ComposeViewport {
            if (session.authenticated) {
                App(organizationName = runtimeConfig.organizationName)
            } else {
                LoginScreen(
                    authApi = koin.get(),
                    serverBaseUrl = runtimeConfig.url,
                    organizationName = runtimeConfig.organizationName,
                    loginFailed = session.afterFailedExchange,
                )
            }
        }
    }
}
