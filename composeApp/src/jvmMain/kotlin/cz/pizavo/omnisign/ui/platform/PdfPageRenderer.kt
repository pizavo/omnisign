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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * JVM desktop implementation of [rememberPdfPageBitmap] backed by Apache PDFBox.
 *
 * Renders the requested page off the main thread via [Dispatchers.Default] and
 * caches the result until any of the parameters change.
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
            Loader.loadPDF(pdfData).use { document ->
                val renderer = PDFRenderer(document)
                val dpi = 72f * scale
                val buffered = renderer.renderImageWithDPI(pageIndex, dpi)
                val stream = ByteArrayOutputStream()
                ImageIO.write(buffered, "PNG", stream)
                org.jetbrains.skia.Image.makeFromEncoded(stream.toByteArray())
                    .toComposeImageBitmap()
            }
        }
    }

    return bitmap
}

