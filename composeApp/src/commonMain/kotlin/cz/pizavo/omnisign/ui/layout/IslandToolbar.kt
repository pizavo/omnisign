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
import cz.pizavo.omnisign.ui.platform.*
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource

private val CompactButtonSize = 32.dp
private val CompactButtonPadding = PaddingValues(2.dp)

/**
 * Seamless top toolbar for the island layout.
 *
 * Renders action icons on the leading side, and settings / theme-toggle buttons
 * on the trailing side. Centred between the two groups are **Sign** and
 * **Timestamp** action buttons. Two transparent [Spacer]s — one on each side of
 * the central buttons — fill the remaining space; on JVM desktop they act as
 * window drag handles via [LocalWindowDragModifier] and report their bounds
 * through [LocalDragAreaCallback].
 *
 * **Platform-adaptive logo placement** — the OmniSign logo is positioned to
 * mirror the native window controls so it never competes with them for visual
 * weight:
 * - **macOS** ([LocalTitleBarLeftInset] > 0): traffic lights occupy the leading
 *   edge, so the logo is placed on the trailing end (rightmost item).
 * - **Windows / Linux** ([LocalTitleBarLeftInset] = 0): window controls occupy
 *   the trailing edge, so the logo is placed on the leading end (leftmost item,
 *   followed by the open-file button).
 *
 * In both cases the logo is positioned so that its horizontal center aligns with
 * the center of the adjacent sidebar icon strip (`4.dp boxPadding + SideBarWidth/2`),
 * keeping the logo visually on the same vertical axis as the sidebar icons.
 *
 * Leading padding respects [LocalTitleBarLeftInset] to avoid the macOS traffic
 * lights; trailing padding respects [LocalTitleBarRightInset] to avoid the
 * Windows/Linux window-control buttons. On macOS full-screen, [LocalTitleBarTopPadding]
 * is animated in and out by a mouse-proximity tracker in the host window — when
 * the cursor enters the top of the screen, a smooth spacer pushes content below
 * the OS auto-hiding title bar; when the cursor leaves the spacer collapses.
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
	val nativeLeftInsetPx = LocalTitleBarLeftInset.current
	val nativeRightInsetPx = LocalTitleBarRightInset.current
	val topPadding = LocalTitleBarTopPadding.current
	val isMacOs = LocalIsMacOs.current
	val logoAlignment = 4.dp + (SideBarWidth - 22.dp) / 2
	val leadingPadding = if (isMacOs) nativeLeftInsetPx.dp else logoAlignment
	val trailingPadding = when {
		nativeRightInsetPx > 0f -> (nativeRightInsetPx + 8).dp
		isMacOs -> logoAlignment
		else -> 4.dp
	}
	val dragModifier = LocalWindowDragModifier.current
	val reportDragArea = LocalDragAreaCallback.current
	val windowControls = LocalWindowControls.current

	Surface(
		modifier = modifier.fillMaxWidth().height(titleBarHeight),
		color = LumoTheme.colors.background,
		contentColor = LumoTheme.colors.onBackground,
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			if (topPadding > 0.dp) {
				Spacer(modifier = Modifier.fillMaxWidth().height(topPadding))
			}

			Row(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					modifier = Modifier
						.fillMaxHeight()
						.padding(start = leadingPadding),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					if (!isMacOs) {
						OmniSignLogoIcon()
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
					modifier = Modifier.padding(end = trailingPadding),
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

					if (isMacOs) {
						OmniSignLogoIcon()
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

/**
 * Standalone OmniSign logo icon used in the toolbar.
 *
 * Extracted to avoid duplication between the leading (Windows/Linux) and
 * trailing (macOS) placements.
 */
@Composable
private fun OmniSignLogoIcon() {
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
}
