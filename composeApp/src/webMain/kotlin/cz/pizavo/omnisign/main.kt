package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.webDataModule
import cz.pizavo.omnisign.ui.branding.brandedTitle
import cz.pizavo.omnisign.ui.platform.LocalStorageProfileSelectionStore
import cz.pizavo.omnisign.ui.platform.MuPdfShim
import cz.pizavo.omnisign.ui.platform.applyWebLocale
import cz.pizavo.omnisign.ui.platform.loadUiPreferences
import cz.pizavo.omnisign.web.resolveWebRuntimeConfig
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.module

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
 *     than the browser locale, then mount the Compose viewport. The server's capabilities (which operations the
 *     server exposes) are fetched by
 *     [cz.pizavo.omnisign.ui.viewmodel.CapabilitiesViewModel] once the UI composes,
 *     narrowing the visible affordances (e.g. hiding the Sign / Timestamp buttons or
 *     the validation panel) to what the server allows.
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
        ComposeViewport {
            App(organizationName = runtimeConfig.organizationName)
        }
    }
}
