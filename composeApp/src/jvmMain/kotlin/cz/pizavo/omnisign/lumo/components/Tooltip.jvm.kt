package cz.pizavo.omnisign.lumo.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import java.awt.Toolkit

/**
 * Returns the current screen width in pixels on the JVM desktop platform.
 * Uses the AWT [Toolkit] to query the default screen size.
 */
@Composable
internal actual fun windowContainerWidthInPx(): Int {
    val density = LocalDensity.current
    val screenWidth = Toolkit.getDefaultToolkit().screenSize.width
    return (screenWidth * density.density).toInt()
}

