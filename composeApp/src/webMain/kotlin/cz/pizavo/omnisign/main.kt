package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.webDataModule
import cz.pizavo.omnisign.ui.platform.MuPdfShim
import org.koin.core.context.startKoin

/**
 * Web (Wasm) entry point for the OmniSign Compose Multiplatform UI.
 *
 * Boot sequence:
 *  1. Touch [MuPdfShim.init] so the MuPDF WebAssembly module's top-level await
 *     resolves before any later sync `getPdfPageCount` / `rememberPdfPageBitmap`
 *     call from the file picker.
 *  2. Start Koin with the platform-agnostic [appModule] (use cases) plus the
 *     web-specific [webDataModule] (Ktor [io.ktor.client.HttpClient] and the
 *     `Remote*Repository` impls). The server base URL is sourced from
 *     [BuildConfig.SERVER_URL], which is empty by default ("same origin" with
 *     the server hosting the bundle) and overridable via the
 *     `OMNISIGN_SERVER_URL` env var at build time.
 *  3. Mount the Compose viewport. The server's capabilities (which operations the
 *     server exposes) are fetched by
 *     [cz.pizavo.omnisign.ui.viewmodel.CapabilitiesViewModel] once the UI composes,
 *     narrowing the visible affordances (e.g. hiding the Sign / Timestamp buttons or
 *     the validation panel) to what the server allows.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MuPdfShim.init()
    startKoin {
        modules(appModule, webDataModule(BuildConfig.SERVER_URL))
    }

    ComposeViewport {
        App()
    }
}
