package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.PdfViewerState
import cz.pizavo.omnisign.ui.platform.rememberPdfPageBitmap
import kotlin.math.roundToInt

/**
 * Base the maximum width of the rendered PDF page before any zoom is applied.
 *
 * At zoom 1× the page will not exceed this width, keeping it comfortable
 * even on ultra-wide or maximized windows. Higher zoom levels scale the cap
 * proportionally, allowing the user to pan horizontally when needed.
 */
private val BASE_MAX_WIDTH = 900.dp

/**
 * Base render scale (DPI multiplier) passed to [rememberPdfPageBitmap].
 * Multiplied by [PdfViewerState.zoomLevel] so higher zoom yields sharper
 * bitmaps, capped at [MAX_RENDER_SCALE] to avoid excessive memory usage.
 */
private const val BASE_RENDER_SCALE = 2f

/**
 * Maximum render scale sent to the platform renderer regardless of zoom.
 */
private const val MAX_RENDER_SCALE = 6f

/**
 * Main PDF viewer content rendered inside [IslandContentCard].
 *
 * When no document is loaded, a placeholder prompt is shown.
 * Otherwise, the current page is rendered as a scrollable image
 * with a navigation and zoom bar at the bottom.
 *
 * The page width is capped at [BASE_MAX_WIDTH] × [PdfViewerState.zoomLevel]
 * so the image never stretches uncontrollably on large screens. When the
 * zoomed width exceeds the available space, the view becomes horizontally
 * scrollable.
 *
 * @param state Current [PdfViewerState] from the view model.
 * @param onPreviousPage Callback to navigate to the previous page.
 * @param onNextPage Callback to navigate to the next page.
 * @param onZoomIn Callback to increase the zoom level.
 * @param onZoomOut Callback to decrease the zoom level.
 * @param onResetZoom Callback to reset the zoom level to 100 %.
 * @param modifier Optional [Modifier] applied to the root container.
 */
@Composable
fun PdfViewerContent(
    state: PdfViewerState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = state.document

    if (document == null) {
        NoDocumentPlaceholder()
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        val renderScale = (BASE_RENDER_SCALE * state.zoomLevel).coerceAtMost(MAX_RENDER_SCALE)
        val bitmap = rememberPdfPageBitmap(
            pdfData = document.data,
            pageIndex = state.currentPage,
            scale = renderScale,
        )

        if (bitmap != null) {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState),
                contentAlignment = Alignment.TopCenter,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Page ${state.currentPage + 1} of ${document.pageCount}",
                    modifier = Modifier
                        .widthIn(max = BASE_MAX_WIDTH * state.zoomLevel)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentScale = ContentScale.FillWidth,
                )
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Rendering page…",
                    style = LumoTheme.typography.body1,
                    color = LumoTheme.colors.textSecondary,
                )
            }
        }

        PdfNavigationBar(
            currentPage = state.currentPage,
            pageCount = document.pageCount,
            fileName = document.name,
            zoomLevel = state.zoomLevel,
            onPreviousPage = onPreviousPage,
            onNextPage = onNextPage,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onResetZoom = onResetZoom,
        )
    }
}

/**
 * Placeholder shown when no PDF document has been opened yet.
 */
@Composable
private fun NoDocumentPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Open a PDF to get started",
            style = LumoTheme.typography.h2,
            color = LumoTheme.colors.textSecondary,
        )
    }
}

/**
 * The bottom navigation bar displaying file name, zoom controls, page indicator,
 * and previous / next buttons.
 *
 * @param currentPage Zero-based current page index.
 * @param pageCount Total number of pages.
 * @param fileName Name of the loaded document.
 * @param zoomLevel Current zoom multiplier (1.0 = 100 %).
 * @param onPreviousPage Callback for the "previous" button.
 * @param onNextPage Callback for the "next" button.
 * @param onZoomIn Callback for the "zoom in" button.
 * @param onZoomOut Callback for the "zoom out" button.
 * @param onResetZoom Callback for clicking the zoom percentage label.
 */
@Composable
private fun PdfNavigationBar(
    currentPage: Int,
    pageCount: Int,
    fileName: String,
    zoomLevel: Float,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = fileName,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
            maxLines = 1,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onZoomOut,
                enabled = zoomLevel > PdfViewerState.MIN_ZOOM,
                variant = ButtonVariant.Ghost,
            ) {
                Text(text = "−")
            }

            Button(
                onClick = onResetZoom,
                variant = ButtonVariant.Ghost,
            ) {
                Text(
                    text = "${(zoomLevel * 100).roundToInt()}%",
                    style = LumoTheme.typography.body2,
                )
            }

            Button(
                onClick = onZoomIn,
                enabled = zoomLevel < PdfViewerState.MAX_ZOOM,
                variant = ButtonVariant.Ghost,
            ) {
                Text(text = "+")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPreviousPage,
                enabled = currentPage > 0,
                variant = ButtonVariant.Ghost,
            ) {
                Text(text = "‹")
            }

            Text(
                text = "${currentPage + 1} / $pageCount",
                style = LumoTheme.typography.body2,
            )

            Button(
                onClick = onNextPage,
                enabled = currentPage < pageCount - 1,
                variant = ButtonVariant.Ghost,
            ) {
                Text(text = "›")
            }
        }
    }
}
