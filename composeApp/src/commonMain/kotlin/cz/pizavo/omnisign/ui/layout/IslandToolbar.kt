package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.platform.LocalDragAreaCallback
import cz.pizavo.omnisign.ui.platform.LocalTitleBarHeight
import cz.pizavo.omnisign.ui.platform.LocalTitleBarRightInset
import cz.pizavo.omnisign.ui.platform.LocalWindowControls
import cz.pizavo.omnisign.ui.platform.LocalWindowDragModifier
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource

private val CompactButtonSize = 32.dp
private val CompactButtonPadding = PaddingValues(2.dp)

/**
 * Seamless top toolbar for the island layout.
 *
 * Renders the application icon and action icons on the leading side, and a
 * settings gear button plus a theme-toggle button on the trailing side.
 * Centred between the two groups are **Sign** and **Timestamp** action
 * buttons. Two transparent [Spacer]s — one on each side of the central
 * buttons — fill the remaining space; on JVM desktop they act as window drag
 * handles (move on drag, maximize / restore on double-click) via
 * [LocalWindowDragModifier] and report their bounds through
 * [LocalDragAreaCallback]. Trailing padding is derived from
 * [LocalTitleBarRightInset] so content does not overlap the native
 * window-control buttons.
 *
 * @param isDarkTheme Whether a dark theme is currently active (controls the toggle icon).
 * @param onToggleTheme Callback invoked when the user clicks the theme-toggle button.
 * @param onOpenFile Callback invoked when the user clicks the folder / open-file button.
 * @param onOpenSettings Callback invoked when the user clicks the settings gear button.
 * @param onSign Callback invoked when the user clicks the Sign button.
 * @param onTimestamp Callback invoked when the user clicks the Timestamp button.
 * @param fileLoaded Whether a PDF document is currently loaded. When `false` the
 *   Sign and Timestamp buttons are disabled.
 * @param modifier Optional [Modifier] applied to the toolbar root.
 */
@Composable
fun IslandToolbar(
	isDarkTheme: Boolean,
	onToggleTheme: () -> Unit,
	onOpenFile: () -> Unit,
	onOpenSettings: () -> Unit,
	onSign: () -> Unit,
	onTimestamp: () -> Unit,
	fileLoaded: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val themeLabel = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
	val titleBarHeight = LocalTitleBarHeight.current
	val nativeRightInsetPx = LocalTitleBarRightInset.current
	val trailingPadding = if (nativeRightInsetPx > 0f) (nativeRightInsetPx + 8).dp else 4.dp
	val dragModifier = LocalWindowDragModifier.current
	val reportDragArea = LocalDragAreaCallback.current
	val windowControls = LocalWindowControls.current
	
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
					.fillMaxHeight()
					.padding(start = 11.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				TooltipBox(
					tooltip = { Tooltip { Text(text = "OmniSign") } },
					state = rememberTooltipState(),
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_omnisign),
						contentDescription = "OmniSign",
						modifier = Modifier.size(22.dp),
						tint = Color.Unspecified,
					)
				}
				
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
			
			Spacer(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					.then(dragModifier)
					.then(
						if (reportDragArea != null) Modifier.onGloballyPositioned { reportDragArea("drag-left", it) }
						else Modifier
					),
			)
			
			Row(
				modifier = Modifier.fillMaxHeight(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				TooltipBox(
					tooltip = { Tooltip { Text(text = "Sign") } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.defaultMinSize(
							minWidth = CompactButtonSize,
							minHeight = CompactButtonSize,
						),
						variant = IconButtonVariant.Ghost,
						enabled = fileLoaded,
						onClick = onSign,
						contentPadding = CompactButtonPadding,
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_sign),
							contentDescription = "Sign document",
							modifier = Modifier.size(22.dp),
						)
					}
				}
				
				TooltipBox(
					tooltip = { Tooltip { Text(text = "Timestamp") } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.defaultMinSize(
							minWidth = CompactButtonSize,
							minHeight = CompactButtonSize,
						),
						variant = IconButtonVariant.Ghost,
						enabled = fileLoaded,
						onClick = onTimestamp,
						contentPadding = CompactButtonPadding,
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_stamp),
							contentDescription = "Timestamp document",
							modifier = Modifier.size(22.dp),
						)
					}
				}
			}
			
			Spacer(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					.then(dragModifier)
					.then(
						if (reportDragArea != null) Modifier.onGloballyPositioned { reportDragArea("drag-right", it) }
						else Modifier
					),
			)
			
			Row(
				modifier = Modifier
					.padding(end = trailingPadding),
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
			
			// Custom window controls (minimize / maximize / close) injected on platforms
			// that run without native window decorations — currently Linux, where JBR's
			// WindowDecorations API is not supported and the window is undecorated.
			windowControls?.invoke()
		}
	}
}
