package cz.pizavo.omnisign

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cz.pizavo.omnisign.ui.platform.MuPdfShim

/**
 * Web (Wasm) entry point for the OmniSign Compose Multiplatform UI.
 *
 * Touches [MuPdfShim.init] before mounting the Compose viewport so that the
 * MuPDF WebAssembly module's top-level await resolves at app boot rather
 * than on the first PDF-open call. This keeps the synchronous expect
 * surface (`getPdfPageCount`) honest in the common case — by the time the
 * user picks a file, the engine is ready.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MuPdfShim.init()
    ComposeViewport {
        App()
    }
}