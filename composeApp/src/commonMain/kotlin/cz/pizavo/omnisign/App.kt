package cz.pizavo.omnisign

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.ui.layout.IslandLayout
import cz.pizavo.omnisign.ui.platform.LocalWindowControls

/**
 * Root composable for the OmniSign application.
 *
 * Wraps the entire UI in [LumoTheme] and renders the IntelliJ "Island"-inspired
 * desktop shell via [IslandLayout]. The dark/light theme toggle state is owned here
 * and threaded down to the layout and theme provider.
 *
 * On JVM desktop the window is undecorated and transparent, so the root content is
 * clipped to a [RoundedCornerShape] for the floating-card aesthetic. The rounding is
 * removed when the window is maximized to avoid visible gaps at screen edges.
 */
@Composable
@Preview
fun App() {
    val systemDark = isSystemInDarkTheme()
    var isDarkTheme by remember { mutableStateOf(systemDark) }
    val isMaximized = LocalWindowControls.current?.isMaximized?.invoke() == true
    val windowShape = if (isMaximized) RectangleShape else RoundedCornerShape(12.dp)

    LumoTheme(isDarkTheme = isDarkTheme) {
        IslandLayout(
            isDarkTheme = isDarkTheme,
            onToggleTheme = { isDarkTheme = !isDarkTheme },
            modifier = Modifier
                .fillMaxSize()
                .clip(windowShape)
                .background(LumoTheme.colors.background)
                .safeContentPadding(),
        )
    }
}