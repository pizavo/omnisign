package cz.pizavo.omnisign.ui.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Scrollable column that shows a visible scrollbar track on platforms that support it.
 *
 * On desktop (JVM) a native [androidx.compose.foundation.VerticalScrollbar] is overlaid on the
 * right edge of the container. On web the browser renders its own scrollbar overlay; no custom
 * track is added.
 *
 * The [modifier] is applied to the outer container. [contentPadding] is applied inside the scroll
 * viewport so padding is not clipped when content overflows.
 *
 * @param modifier Applied to the outer layout container (e.g. `Modifier.fillMaxSize()`).
 * @param verticalArrangement Vertical spacing strategy for the column children.
 * @param horizontalAlignment Horizontal alignment for the column children.
 * @param contentPadding Inner padding applied inside the scroll viewport.
 * @param content Column content slot.
 */
@Composable
expect fun VerticalScrollableColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable ColumnScope.() -> Unit,
)

