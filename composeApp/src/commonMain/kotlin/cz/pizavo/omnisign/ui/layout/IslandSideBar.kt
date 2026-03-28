package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.model.SidePanel
import org.jetbrains.compose.resources.painterResource

/** Width of the narrow sidebar icon strip on each side of the layout. */
internal val SideBarWidth = 36.dp
private val SideBarButtonSize = 32.dp
private val SideBarIconSize = 22.dp
private val SideBarButtonPadding = PaddingValues(2.dp)

/**
 * Narrow vertical icon strip used as a sidebar in the island layout.
 *
 * Renders one ghost [IconButton] per [SidePanel] entry, wrapped in a [TooltipBox]
 * that shows the panel label on hover. The currently active panel's icon receives a
 * highlighted surface-colored background to indicate selection.
 *
 * Panels whose [SidePanel.pinToBottom] flag is `true` are pushed to the bottom of the
 * column via a weighted spacer, producing an IntelliJ-style split sidebar.
 *
 * Reusable for both left and right sides — pass the filtered list of panels.
 *
 * @param panels Panels to display as icons, pre-filtered by [PanelSide][cz.pizavo.omnisign.ui.model.PanelSide].
 * @param activePanel The currently expanded panel on this side, or `null` if collapsed.
 * @param onPanelToggle Callback invoked when an icon is clicked; receives the toggled panel.
 * @param tooltipPlacement Edge on which tooltips appear. Use [TooltipPlacement.End] for the
 *   left sidebar and [TooltipPlacement.Start] for the right sidebar so tooltips point inwards.
 * @param modifier Optional [Modifier] applied to the sidebar column.
 */
@Composable
fun IslandSideBar(
	panels: List<SidePanel>,
	activePanel: SidePanel?,
	onPanelToggle: (SidePanel) -> Unit,
	tooltipPlacement: TooltipPlacement = TooltipPlacement.Top,
	modifier: Modifier = Modifier,
) {
	val topPanels = panels.filter { !it.pinToBottom }
	val bottomPanels = panels.filter { it.pinToBottom }
	
	Column(
		modifier = modifier
			.fillMaxHeight()
			.width(SideBarWidth)
			.padding(vertical = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		topPanels.forEach { panel ->
			SideBarIcon(
				panel = panel,
				isSelected = panel == activePanel,
				onClick = { onPanelToggle(panel) },
				tooltipPlacement = tooltipPlacement,
			)
		}
		
		if (bottomPanels.isNotEmpty()) {
			Spacer(modifier = Modifier.weight(1f))
			
			bottomPanels.forEach { panel ->
				SideBarIcon(
					panel = panel,
					isSelected = panel == activePanel,
					onClick = { onPanelToggle(panel) },
					tooltipPlacement = tooltipPlacement,
				)
			}
		}
	}
}

/**
 * Single sidebar icon button with tooltip and selection highlight.
 *
 * @param panel The [SidePanel] this icon represents.
 * @param isSelected Whether this panel is currently active.
 * @param onClick Callback invoked when the icon is clicked.
 * @param tooltipPlacement Edge on which the tooltip appears.
 */
@Composable
private fun SideBarIcon(
	panel: SidePanel,
	isSelected: Boolean,
	onClick: () -> Unit,
	tooltipPlacement: TooltipPlacement = TooltipPlacement.Top,
) {
	val iconModifier = if (isSelected) {
		Modifier
			.clip(RoundedCornerShape(8.dp))
			.background(LumoTheme.colors.surface)
	} else {
		Modifier
	}
	
	TooltipBox(
		positionProvider = rememberTooltipPositionProvider(tooltipPlacement),
		tooltip = { Tooltip { Text(text = panel.label) } },
		state = rememberTooltipState(),
	) {
		IconButton(
			modifier = iconModifier.defaultMinSize(
				minWidth = SideBarButtonSize,
				minHeight = SideBarButtonSize,
			),
			variant = IconButtonVariant.Ghost,
			onClick = onClick,
			contentPadding = SideBarButtonPadding,
		) {
			Icon(
				painter = painterResource(panel.icon),
				contentDescription = panel.contentDescription,
				modifier = Modifier.size(SideBarIconSize),
			)
		}
	}
}
