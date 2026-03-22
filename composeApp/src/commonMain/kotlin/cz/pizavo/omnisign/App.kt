package cz.pizavo.omnisign

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.Text
import org.jetbrains.compose.resources.painterResource

import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.compose_multiplatform

/**
 * Root composable for the OmniSign application.
 * Wraps the entire UI in [LumoTheme] to provide consistent theming.
 */
@Composable
@Preview
fun App() {
    LumoTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(LumoTheme.colors.background)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                }
            }
        }
    }
}