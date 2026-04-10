package cz.pizavo.omnisign.ui.platform

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import cz.pizavo.omnisign.lumo.LumoTheme

/**
 * Desktop (JVM) implementation: wraps the scrollable column in a [Box] and overlays a
 * [VerticalScrollbar] on the right edge so the user can see scroll position and drag to navigate.
 *
 * In light mode the thumb is derived from the theme's `textSecondary` colour (`Gray700`) for good
 * contrast on the white surface. In dark mode the thumb uses the theme's `background` colour
 * (`Gray900 ≈ #282828`) so it sits subtly against the very dark surface (`#1E1E1E`).
 */
@Composable
actual fun VerticalScrollableColumn(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical,
    horizontalAlignment: Alignment.Horizontal,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val colors = LumoTheme.colors
    val isDark = colors.surface.luminance() < 0.5f
    val (unhoverColor, hoverColor) = if (isDark) {
        colors.background.copy(alpha = 0.8f) to colors.background
    } else {
        colors.textSecondary.copy(alpha = 0.4f) to colors.textSecondary.copy(alpha = 0.9f)
    }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        CompositionLocalProvider(
            LocalScrollbarStyle provides LocalScrollbarStyle.current.copy(
                unhoverColor = unhoverColor,
                hoverColor = hoverColor,
            )
        ) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}


