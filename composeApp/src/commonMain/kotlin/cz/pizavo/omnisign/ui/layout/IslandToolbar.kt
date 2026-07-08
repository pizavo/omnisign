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
import cz.pizavo.omnisign.ui.branding.LocalOrganizationName
import cz.pizavo.omnisign.ui.branding.LocalServerOrganizationName
import cz.pizavo.omnisign.ui.branding.brandedTitle
import cz.pizavo.omnisign.ui.platform.*
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
 * @param canSign Whether the server permits signing. When `false` the Sign button is not
 *   rendered at all (the web target hides operations the server disallows; desktop passes `true`).
 * @param canTimestamp Whether the server permits timestamping / extension. When `false` the
 *   Timestamp button is not rendered.
 * @param fileLoaded Whether a PDF document is currently loaded. When `false` the rendered
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
	canSign: Boolean = true,
	canTimestamp: Boolean = true,
	fileLoaded: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val themeLabel = if (isDarkTheme) stringResource(Res.string.toolbar_switch_to_light_theme) else stringResource(Res.string.toolbar_switch_to_dark_theme)
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
						tooltip = { Tooltip { Text(text = stringResource(Res.string.toolbar_open_file)) } },
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
								contentDescription = stringResource(Res.string.toolbar_open_file),
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
					if (canSign) {
						ToolbarActionButton(
							tooltip = stringResource(Res.string.action_sign),
							icon = Res.drawable.icon_sign,
							contentDescription = stringResource(Res.string.toolbar_sign_description),
							enabled = fileLoaded,
							onClick = onSign,
						)
					}

					if (canTimestamp) {
						ToolbarActionButton(
							tooltip = stringResource(Res.string.toolbar_timestamp_tooltip),
							icon = Res.drawable.icon_stamp,
							contentDescription = stringResource(Res.string.toolbar_timestamp_description),
							enabled = fileLoaded,
							onClick = onTimestamp,
						)
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
						tooltip = { Tooltip { Text(text = stringResource(Res.string.label_settings)) } },
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
								contentDescription = stringResource(Res.string.toolbar_settings_description),
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
				
				windowControls?.invoke()
			}
		}
	}
}

/**
 * A compact ghost icon button for a centred toolbar action (Sign / Timestamp), wrapped in a
 * hover tooltip. Extracted so the two near-identical action buttons share one definition and
 * can be rendered conditionally on the server's capabilities.
 *
 * @param tooltip Tooltip text shown on hover.
 * @param icon Icon drawable resource.
 * @param contentDescription Accessibility description for the icon.
 * @param enabled Whether the button is interactive.
 * @param onClick Click handler invoked when the button is pressed.
 */
@Composable
private fun ToolbarActionButton(
	tooltip: String,
	icon: DrawableResource,
	contentDescription: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	TooltipBox(
		tooltip = { Tooltip { Text(text = tooltip) } },
		state = rememberTooltipState(),
	) {
		IconButton(
			modifier = Modifier.defaultMinSize(
				minWidth = CompactButtonSize,
				minHeight = CompactButtonSize,
			),
			variant = IconButtonVariant.Ghost,
			enabled = enabled,
			onClick = onClick,
			contentPadding = CompactButtonPadding,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = contentDescription,
				modifier = Modifier.size(22.dp),
			)
		}
	}
}

/**
 * Standalone OmniSign logo icon used in the toolbar.
 *
 * Extracted to avoid duplication between the leading (Windows/Linux) and
 * trailing (macOS) placements. Its tooltip and accessibility label mirror the browser tab title via
 * [brandedTitle], so under a provider's deploy-time branding they read the same de-duplicated
 * `"<deployer> · <operator> · OmniSign"` chain, and plain `"OmniSign"` otherwise.
 */
@Composable
private fun OmniSignLogoIcon() {
	val title = brandedTitle(LocalOrganizationName.current, LocalServerOrganizationName.current)
	TooltipBox(
		tooltip = { Tooltip { Text(text = title) } },
		state = rememberTooltipState(),
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_omnisign),
			contentDescription = title,
			modifier = Modifier.size(22.dp),
			tint = Color.Unspecified,
		)
	}
}
