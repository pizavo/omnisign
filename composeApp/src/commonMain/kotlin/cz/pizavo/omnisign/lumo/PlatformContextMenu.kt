package cz.pizavo.omnisign.lumo

import androidx.compose.runtime.Composable

/**
 * Provides a platform-appropriate, app-styled text selection / context menu around [content].
 *
 * On desktop this installs a Lumo-styled context-menu representation (see the desktop actual)
 * so the right-click selection menu matches the app's own popups. On web the actual is a
 * passthrough — the browser renders its own native menu, which Compose does not control.
 *
 * Must be called inside [LumoTheme]'s providers so the desktop representation can read the
 * active [LumoTheme.colors] and [LumoTheme.typography].
 */
@Composable
expect fun ProvidePlatformContextMenu(content: @Composable () -> Unit)
