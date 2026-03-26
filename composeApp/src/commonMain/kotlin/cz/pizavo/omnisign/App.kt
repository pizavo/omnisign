package cz.pizavo.omnisign

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.ui.layout.IslandLayout

/**
 * Root composable for the OmniSign application.
 *
 * Wraps the entire UI in [LumoTheme] and renders the IntelliJ "Island"-inspired
 * desktop shell via [IslandLayout]. The dark/light theme toggle state is owned
 * here and threaded down to the layout and theme provider.
 */
@Composable
@Preview
fun App() {
    val systemDark = isSystemInDarkTheme()
    var isDarkTheme by remember { mutableStateOf(systemDark) }

    LumoTheme(isDarkTheme = isDarkTheme) {
        IslandLayout(
            isDarkTheme = isDarkTheme,
            onToggleTheme = { isDarkTheme = !isDarkTheme },
            modifier = Modifier
                .fillMaxSize()
                .background(LumoTheme.colors.background)
                .safeContentPadding(),
        )
    }
}