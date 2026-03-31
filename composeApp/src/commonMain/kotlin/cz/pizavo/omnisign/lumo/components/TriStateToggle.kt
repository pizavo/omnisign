package cz.pizavo.omnisign.lumo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_check
import omnisign.composeapp.generated.resources.icon_slash
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Possible states for the [TriStateToggle].
 *
 * @property label Human-readable label shown in the segment tooltip.
 */
enum class TriToggleState(val label: String) {
	/**
	 * Explicitly disabled — overrides the inherited value to off.
	 */
	DISABLED("Disable"),

	/**
	 * Inherits the value from the parent (global) configuration.
	 */
	INHERIT("Inherit"),

	/**
	 * Explicitly enabled — overrides the inherited value to on.
	 */
	ENABLED("Enable"),
}

/**
 * A segmented three-position toggle control (Disable / Inherit / Enable).
 *
 * Rendered as three adjacent segments, each containing an icon:
 * - Left (✕): [TriToggleState.DISABLED] — red when selected.
 * - Center (/): [TriToggleState.INHERIT] — neutral when selected.
 * - Right (✓): [TriToggleState.ENABLED] — green when selected.
 *
 * The selected segment shows a vivid background color with a contrasting
 * icon; unselected segments use a muted background with a dimmed icon.
 *
 * @param state Current toggle state.
 * @param onStateChange Callback invoked when the user selects a new state.
 * @param modifier Modifier applied to the toggle root.
 * @param enabled Whether the toggle accepts user input.
 * @param colors Color configuration for the toggle.
 */
@Composable
fun TriStateToggle(
	state: TriToggleState,
	onStateChange: (TriToggleState) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	colors: TriStateToggleColors = TriStateToggleDefaults.colors(),
) {
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(TriStateToggleDefaults.SegmentGap),
	) {
		TriToggleState.entries.forEach { segmentState ->
			val isSelected = state == segmentState
			val shape = when (segmentState) {
				TriToggleState.DISABLED -> TriStateToggleDefaults.StartShape
				TriToggleState.INHERIT -> TriStateToggleDefaults.MiddleShape
				TriToggleState.ENABLED -> TriStateToggleDefaults.EndShape
			}
			val bgColor = if (isSelected) {
				colors.selectedBackground(enabled, segmentState)
			} else {
				colors.unselectedBackground(enabled)
			}
			val iconTint = if (isSelected) {
				colors.selectedIcon(enabled, segmentState)
			} else {
				colors.unselectedIcon(enabled, segmentState)
			}

			val segment = @Composable {
				Box(
					modifier = Modifier
						.size(TriStateToggleDefaults.SegmentSize)
						.clip(shape)
						.background(bgColor)
						.then(
							if (enabled && !isSelected) {
								Modifier.clickable(
									interactionSource = remember { MutableInteractionSource() },
									indication = null,
								) { onStateChange(segmentState) }
							} else {
								Modifier
							}
						),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = segmentState.icon(),
						contentDescription = segmentState.name,
						modifier = Modifier.size(TriStateToggleDefaults.IconSize),
						tint = iconTint,
					)
				}
			}

			if (enabled) {
				TooltipBox(
					tooltip = { Tooltip { Text(text = segmentState.label) } },
					state = rememberTooltipState(),
				) { segment() }
			} else {
				segment()
			}
		}
	}
}

/**
 * Resolve the icon painter for a [TriToggleState] segment.
 */
@Composable
private fun TriToggleState.icon(): Painter = when (this) {
	TriToggleState.DISABLED -> painterResource(Res.drawable.icon_x)
	TriToggleState.INHERIT -> painterResource(Res.drawable.icon_slash)
	TriToggleState.ENABLED -> painterResource(Res.drawable.icon_check)
}

/**
 * Default dimensions and factory for [TriStateToggle] colors.
 */
object TriStateToggleDefaults {
	/** Size of each segment box. */
	val SegmentSize = 28.dp

	/** Icon size within each segment. */
	val IconSize = 16.dp

	/** Gap between adjacent segments. */
	val SegmentGap = 2.dp

	/** Corner radius for the rounded outer edges. */
	private val CornerRadius = 6.dp

	/** Shape for the left (DISABLED) segment. */
	val StartShape = RoundedCornerShape(topStart = CornerRadius, bottomStart = CornerRadius)

	/** Shape for the center (INHERIT) segment. */
	val MiddleShape = RoundedCornerShape(0.dp)

	/** Shape for the right (ENABLED) segment. */
	val EndShape = RoundedCornerShape(topEnd = CornerRadius, bottomEnd = CornerRadius)

	/**
	 * Create a [TriStateToggleColors] with Lumo theme defaults.
	 *
	 * @param selectedDisabledBg Background when DISABLED is selected.
	 * @param selectedInheritBg Background when INHERIT is selected.
	 * @param selectedEnabledBg Background when ENABLED is selected.
	 * @param unselectedBg Background for unselected segments.
	 * @param selectedIconColor Icon color on a selected segment.
	 * @param unselectedDisabledIcon Icon color for the unselected DISABLED segment.
	 * @param unselectedInheritIcon Icon color for the unselected INHERIT segment.
	 * @param unselectedEnabledIcon Icon color for the unselected ENABLED segment.
	 * @param disabledStateBg Background when the whole toggle is non-interactive.
	 * @param disabledStateIcon Icon color when the whole toggle is non-interactive.
	 */
	@Composable
	fun colors(
		selectedDisabledBg: Color = LumoTheme.colors.error,
		selectedInheritBg: Color = LumoTheme.colors.secondary,
		selectedEnabledBg: Color = LumoTheme.colors.success,
		unselectedBg: Color = LumoTheme.colors.outline,
		selectedIconColor: Color = LumoTheme.colors.white,
		unselectedDisabledIcon: Color = LumoTheme.colors.error,
		unselectedInheritIcon: Color = LumoTheme.colors.textSecondary,
		unselectedEnabledIcon: Color = LumoTheme.colors.success,
		disabledStateBg: Color = LumoTheme.colors.disabled,
		disabledStateIcon: Color = LumoTheme.colors.onDisabled,
	): TriStateToggleColors =
		TriStateToggleColors(
			selectedDisabledBg = selectedDisabledBg,
			selectedInheritBg = selectedInheritBg,
			selectedEnabledBg = selectedEnabledBg,
			unselectedBg = unselectedBg,
			selectedIconColor = selectedIconColor,
			unselectedDisabledIcon = unselectedDisabledIcon,
			unselectedInheritIcon = unselectedInheritIcon,
			unselectedEnabledIcon = unselectedEnabledIcon,
			disabledStateBg = disabledStateBg,
			disabledStateIcon = disabledStateIcon,
		)
}

/**
 * Color configuration for the [TriStateToggle] segments.
 */
@Immutable
class TriStateToggleColors(
	private val selectedDisabledBg: Color,
	private val selectedInheritBg: Color,
	private val selectedEnabledBg: Color,
	private val unselectedBg: Color,
	private val selectedIconColor: Color,
	private val unselectedDisabledIcon: Color,
	private val unselectedInheritIcon: Color,
	private val unselectedEnabledIcon: Color,
	private val disabledStateBg: Color,
	private val disabledStateIcon: Color,
) {
	/**
	 * Resolve the background color for a selected segment.
	 */
	@Stable
	internal fun selectedBackground(enabled: Boolean, segment: TriToggleState): Color =
		if (!enabled) disabledStateBg
		else when (segment) {
			TriToggleState.DISABLED -> selectedDisabledBg
			TriToggleState.INHERIT -> selectedInheritBg
			TriToggleState.ENABLED -> selectedEnabledBg
		}

	/**
	 * Resolve the background color for an unselected segment.
	 */
	@Stable
	internal fun unselectedBackground(enabled: Boolean): Color =
		if (!enabled) disabledStateBg else unselectedBg

	/**
	 * Resolve the icon tint for a selected segment.
	 */
	@Stable
	internal fun selectedIcon(enabled: Boolean, @Suppress("UNUSED_PARAMETER") segment: TriToggleState): Color =
		if (!enabled) disabledStateIcon else selectedIconColor

	/**
	 * Resolve the icon tint for an unselected segment.
	 */
	@Stable
	internal fun unselectedIcon(enabled: Boolean, segment: TriToggleState): Color =
		if (!enabled) disabledStateIcon
		else when (segment) {
			TriToggleState.DISABLED -> unselectedDisabledIcon
			TriToggleState.INHERIT -> unselectedInheritIcon
			TriToggleState.ENABLED -> unselectedEnabledIcon
		}
}

@Preview
@Composable
private fun TriStateTogglePreview() {
	LumoTheme {
		Column(modifier = Modifier.padding(16.dp)) {
			TriStateToggle(state = TriToggleState.ENABLED, onStateChange = {})
			Spacer(modifier = Modifier.size(8.dp))
			TriStateToggle(state = TriToggleState.DISABLED, onStateChange = {})
			Spacer(modifier = Modifier.size(8.dp))
			TriStateToggle(state = TriToggleState.INHERIT, onStateChange = {})
			Spacer(modifier = Modifier.size(8.dp))
			TriStateToggle(state = TriToggleState.ENABLED, onStateChange = {}, enabled = false)
		}
	}
}
