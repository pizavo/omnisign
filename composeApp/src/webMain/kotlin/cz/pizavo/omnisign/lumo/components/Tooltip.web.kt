package cz.pizavo.omnisign.lumo.components

import androidx.compose.runtime.Composable
import kotlinx.browser.window

/**
 * Returns the current browser window inner width in pixels on the Wasm/JS platform.
 */
@Composable
internal actual fun windowContainerWidthInPx(): Int {
    return window.innerWidth
}

