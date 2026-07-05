package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.webDataModule
import cz.pizavo.omnisign.ui.platform.LocalStorageProfileSelectionStore
import cz.pizavo.omnisign.ui.platform.MuPdfShim
import cz.pizavo.omnisign.web.resolveServerBaseUrl
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
 *     `Remote*Repository` impls), anchored at the resolved server URL.
 *  4. Mount the Compose viewport. The server's capabilities (which operations the
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
        val serverBaseUrl = resolveServerBaseUrl(BuildConfig.SERVER_URL)
        startKoin {
            modules(appModule, webDataModule(serverBaseUrl), webPlatformModule)
        }
        ComposeViewport {
            App()
        }
    }
}
