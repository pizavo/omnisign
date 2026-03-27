package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import cz.pizavo.omnisign.ui.platform.LocalTitleBarHeight
import cz.pizavo.omnisign.ui.platform.LocalTitleBarHitTest
import cz.pizavo.omnisign.ui.platform.LocalTitleBarRightInset
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_folder
import omnisign.composeapp.generated.resources.icon_moon
import omnisign.composeapp.generated.resources.icon_omnisign
import omnisign.composeapp.generated.resources.icon_settings
import omnisign.composeapp.generated.resources.icon_sun
import org.jetbrains.compose.resources.painterResource

private val CompactButtonSize = 28.dp
private val CompactButtonPadding = PaddingValues(2.dp)

/**
 * Seamless top toolbar for the island layout.
 *
 * Renders the application icon and action icons on the leading side, and a
 * settings gear button plus a theme-toggle button on the trailing side. On JVM
 * desktop the toolbar sits inside the JBR custom title bar area — the OS handles
 * window dragging natively and interactive controls are excluded via
 * [LocalTitleBarHitTest]. Trailing padding is derived from [LocalTitleBarRightInset]
 * so content does not overlap the native window-control buttons.
 *
 * @param isDarkTheme Whether a dark theme is currently active (controls the toggle icon).
 * @param onToggleTheme Callback invoked when the user clicks the theme-toggle button.
 * @param onOpenFile Callback invoked when the user clicks the folder / open-file button.
 * @param onOpenSettings Callback invoked when the user clicks the settings gear button.
 * @param modifier Optional [Modifier] applied to the toolbar root.
 */
@Composable
fun IslandToolbar(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeLabel = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
    val hitTestCallback = LocalTitleBarHitTest.current
    val titleBarHeight = LocalTitleBarHeight.current
    val nativeRightInsetPx = LocalTitleBarRightInset.current
    val trailingPadding = if (nativeRightInsetPx > 0f) (nativeRightInsetPx + 8).dp else 4.dp

    Surface(
        modifier = modifier.fillMaxWidth().height(titleBarHeight),
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
                    .padding(start = 11.dp)
                    .onGloballyPositioned { coords ->
                        hitTestCallback?.invoke("toolbar-left", coords.boundsInWindow())
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                        onClick = onOpenFile,
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
                modifier = Modifier
                    .padding(end = trailingPadding)
                    .onGloballyPositioned { coords ->
                        hitTestCallback?.invoke("toolbar-right", coords.boundsInWindow())
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TooltipBox(
                    tooltip = { Tooltip { Text(text = "Settings") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.defaultMinSize(
                            minWidth = CompactButtonSize,
                            minHeight = CompactButtonSize,
                        ),
                        variant = IconButtonVariant.Ghost,
                        onClick = onOpenSettings,
                        contentPadding = CompactButtonPadding,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_settings),
                            contentDescription = "Open settings",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

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
            }
        }
    }
}
