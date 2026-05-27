package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.webDataModule
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.ui.platform.MuPdfShim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform.getKoin

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
 *  3. Fire a fire-and-forget round-trip against [CapabilitiesRepository.get]
 *     and log the response. This is purely a wire-verification surface for
 *     chunk-A of the FE-for-server build-out; user-visible consumption of the
 *     capabilities (hiding disabled operation buttons, redirecting to login
 *     when `authEnabled = true`, …) lands in subsequent chunks.
 *  4. Mount the Compose viewport.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MuPdfShim.init()
    startKoin {
        modules(appModule, webDataModule(BuildConfig.SERVER_URL))
    }

    CoroutineScope(Dispatchers.Default).launch {
        runCatching {
            getKoin().get<CapabilitiesRepository>().get()
        }.fold(
            onSuccess = { caps ->
                println("OmniSign capabilities: allowedOperations=${caps.allowedOperations}, profiles=${caps.profiles}, maxFileSize=${caps.maxFileSize}, authEnabled=${caps.authEnabled}")
            },
            onFailure = { err ->
                println("OmniSign capabilities fetch failed: ${err::class.simpleName}: ${err.message}")
            },
        )
    }

    ComposeViewport {
        App()
    }
}
