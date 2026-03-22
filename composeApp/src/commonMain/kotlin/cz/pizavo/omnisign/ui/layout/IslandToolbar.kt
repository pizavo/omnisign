package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.ui.platform.LocalWindowControls
import cz.pizavo.omnisign.ui.platform.LocalWindowDragModifier
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_folder
import omnisign.composeapp.generated.resources.icon_moon
import omnisign.composeapp.generated.resources.icon_omnisign
import omnisign.composeapp.generated.resources.icon_sun
import omnisign.composeapp.generated.resources.icon_window_maximize
import omnisign.composeapp.generated.resources.icon_window_minimize
import omnisign.composeapp.generated.resources.icon_window_restore
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

private val CompactButtonSize = 28.dp
private val CompactButtonPadding = PaddingValues(2.dp)

/**
 * Seamless top toolbar for the island layout.
 *
 * Renders the application title on the leading side, action icons in the middle,
 * and optional window-control buttons (minimize, maximize/restore, close) on the
 * trailing side when [LocalWindowControls] is provided (JVM desktop).
 *
 * The toolbar doubles as a window drag handle via [LocalWindowDragModifier],
 * allowing the user to move the undecorated window by dragging the toolbar area.
 *
 * @param isDarkTheme Whether a dark theme is currently active (controls the toggle icon).
 * @param onToggleTheme Callback invoked when the user clicks the theme-toggle button.
 * @param modifier Optional [Modifier] applied to the toolbar root.
 */
@Composable
fun IslandToolbar(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeLabel = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
    val windowControls = LocalWindowControls.current
    val dragModifier = LocalWindowDragModifier.current

    Surface(
        modifier = modifier.fillMaxWidth().height(40.dp),
        color = LumoTheme.colors.background,
        contentColor = LumoTheme.colors.onBackground,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(dragModifier)
                    .padding(start = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_omnisign),
                    contentDescription = "OmniSign",
                    modifier = Modifier.size(22.dp),
                    tint = Color.Unspecified,
                )

                TooltipBox(
                    tooltip = { Tooltip { Text(text = "Open file") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.defaultMinSize(
                            minWidth = CompactButtonSize,
                            minHeight = CompactButtonSize,
                        ),
                        variant = IconButtonVariant.Ghost,
                        onClick = { },
                        contentPadding = CompactButtonPadding,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_folder),
                            contentDescription = "Open file",
                            modifier = Modifier.size(22.dp),
                            tint = LumoTheme.colors.icons.folder,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TooltipBox(
                    tooltip = { Tooltip { Text(text = themeLabel) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.defaultMinSize(
                            minWidth = CompactButtonSize,
                            minHeight = CompactButtonSize,
                        ),
                        variant = IconButtonVariant.Ghost,
                        onClick = onToggleTheme,
                        contentPadding = CompactButtonPadding,
                    ) {
                        Icon(
                            painter = if (isDarkTheme) painterResource(Res.drawable.icon_sun)
                            else painterResource(Res.drawable.icon_moon),
                            contentDescription = themeLabel,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                if (windowControls != null) {
                    Spacer(modifier = Modifier.width(2.dp))
                    WindowControlButtons(controls = windowControls)
                }
            }
        }
    }
}

/**
 * Row of minimize / maximize-or-restore / close buttons for an undecorated window.
 *
 * Only rendered when [LocalWindowControls] provides a non-null value.
 *
 * @param controls Platform-supplied [cz.pizavo.omnisign.ui.platform.WindowControls] callbacks.
 */
@Composable
private fun WindowControlButtons(
    controls: cz.pizavo.omnisign.ui.platform.WindowControls,
) {
    val isMaximized = controls.isMaximized()
    val buttonModifier = Modifier.defaultMinSize(
        minWidth = CompactButtonSize,
        minHeight = CompactButtonSize,
    )

    TooltipBox(
        tooltip = { Tooltip { Text(text = "Minimize") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            modifier = buttonModifier,
            variant = IconButtonVariant.Ghost,
            onClick = controls.onMinimize,
            contentPadding = CompactButtonPadding,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_window_minimize),
                contentDescription = "Minimize window",
                modifier = Modifier.size(16.dp),
            )
        }
    }

    TooltipBox(
        tooltip = { Tooltip { Text(text = if (isMaximized) "Restore" else "Maximize") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            modifier = buttonModifier,
            variant = IconButtonVariant.Ghost,
            onClick = controls.onMaximize,
            contentPadding = CompactButtonPadding,
        ) {
            Icon(
                painter = if (isMaximized) painterResource(Res.drawable.icon_window_restore)
                else painterResource(Res.drawable.icon_window_maximize),
                contentDescription = if (isMaximized) "Restore window" else "Maximize window",
                modifier = Modifier.size(16.dp),
            )
        }
    }

    TooltipBox(
        tooltip = { Tooltip { Text(text = "Close") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            modifier = buttonModifier,
            variant = IconButtonVariant.DestructiveGhost,
            onClick = controls.onClose,
            contentPadding = CompactButtonPadding,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_x),
                contentDescription = "Close window",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
