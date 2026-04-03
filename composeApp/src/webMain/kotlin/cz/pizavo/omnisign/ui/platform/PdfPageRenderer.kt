package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Wasm/JS stub for [rememberPdfPageBitmap].
 *
 * Not yet implemented — always returns `null`.
 */
@Composable
actual fun rememberPdfPageBitmap(
    pdfData: ByteArray,
    pageIndex: Int,
    scale: Float,
): ImageBitmap? = null

