package cz.pizavo.omnisign.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Renders a single page of a PDF document and remembers the resulting bitmap.
 *
 * The rendering is performed asynchronously; the function returns `null` while
 * the bitmap is being prepared and the actual [ImageBitmap] once it is ready.
 * A new render is triggered whenever [pdfData], [pageIndex], or [scale] change.
 *
 * @param pdfData Raw bytes of the PDF document.
 * @param pageIndex Zero-based page index to render.
 * @param scale Multiplier applied to the base 72 DPI resolution (e.g., 2.0 → 144 DPI).
 * @return The rendered page bitmap, or `null` while loading.
 */
@Composable
expect fun rememberPdfPageBitmap(
    pdfData: ByteArray,
    pageIndex: Int,
    scale: Float = 2f,
): ImageBitmap?

