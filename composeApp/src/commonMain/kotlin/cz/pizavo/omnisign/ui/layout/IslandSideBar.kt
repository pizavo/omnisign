package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.ui.model.SidePanel
import org.jetbrains.compose.resources.painterResource

private val SideBarWidth = 36.dp
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
 * Reusable for both left and right sides — simply pass the filtered list of panels.
 *
 * @param panels Panels to display as icons, pre-filtered by [PanelSide][cz.pizavo.omnisign.ui.model.PanelSide].
 * @param activePanel The currently expanded panel on this side, or `null` if collapsed.
 * @param onPanelToggle Callback invoked when an icon is clicked; receives the toggled panel.
 * @param modifier Optional [Modifier] applied to the sidebar column.
 */
@Composable
fun IslandSideBar(
    panels: List<SidePanel>,
    activePanel: SidePanel?,
    onPanelToggle: (SidePanel) -> Unit,
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
            SideBarIcon(panel = panel, isSelected = panel == activePanel, onClick = { onPanelToggle(panel) })
        }

        if (bottomPanels.isNotEmpty()) {
            Spacer(modifier = Modifier.weight(1f))

            bottomPanels.forEach { panel ->
                SideBarIcon(panel = panel, isSelected = panel == activePanel, onClick = { onPanelToggle(panel) })
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
 */
@Composable
private fun SideBarIcon(
    panel: SidePanel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val iconModifier = if (isSelected) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LumoTheme.colors.surface)
    } else {
        Modifier
    }

    TooltipBox(
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
