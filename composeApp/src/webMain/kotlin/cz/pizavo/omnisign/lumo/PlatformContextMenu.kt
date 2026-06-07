package cz.pizavo.omnisign.lumo

import androidx.compose.runtime.Composable

/**
 * Web: passthrough. The browser renders its own native selection / context menu, which Compose
 * does not control, so [content] is emitted unchanged.
 */
@Composable
actual fun ProvidePlatformContextMenu(content: @Composable () -> Unit) {
    content()
}
