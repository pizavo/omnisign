package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wasm/JS implementation of [rememberPdfPageBitmap] backed by the
 * [MuPdfShim] JavaScript binding around the
 * [`mupdf`](https://www.npmjs.com/package/mupdf) WebAssembly engine.
 *
 * Mirrors the JVM/PDFBox actual: the requested page is rendered off the main
 * thread via [Dispatchers.Default], cached until any input changes, and
 * decoded through Skia (`Image.makeFromEncoded`) to a Compose [ImageBitmap].
 * MuPDF's pixel data round-trips as PNG bytes — the same encoding the JVM
 * path uses — so the Skia decode step is identical across platforms.
 *
 * `scale = 2f` corresponds to 144 DPI, matching the JVM default.
 */
@Composable
actual fun rememberPdfPageBitmap(
    pdfData: ByteArray,
    pageIndex: Int,
    scale: Float,
): ImageBitmap? {
    var bitmap by remember(pdfData, pageIndex, scale) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(pdfData, pageIndex, scale) {
        bitmap = withContext(Dispatchers.Default) {
            val png = MuPdfShim.renderPagePng(
                bytes = pdfData.toUint8Array(),
                pageIndex = pageIndex,
                scale = scale.toDouble(),
            ).toByteArray()
            org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap()
        }
    }

    return bitmap
}
