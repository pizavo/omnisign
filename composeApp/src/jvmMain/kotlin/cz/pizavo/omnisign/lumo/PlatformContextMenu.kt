package cz.pizavo.omnisign.lumo

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * Desktop: installs the Lumo-styled [LumoContextMenuRepresentation] via
 * [LocalContextMenuRepresentation] so the right-click selection / context menu matches the
 * app's design instead of Compose's generic default.
 */
@Composable
actual fun ProvidePlatformContextMenu(content: @Composable () -> Unit) {
    val representation = remember { LumoContextMenuRepresentation() }
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides representation,
        content = content,
    )
}
