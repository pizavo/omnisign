package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.PanelSide
import cz.pizavo.omnisign.ui.model.SidePanel

/**
 * Root shell composable that implements the IntelliJ "Island" layout.
 *
 * The layout consists of:
 * - A seamless [IslandToolbar] at the top.
 * - A left [IslandSideBar] with icon buttons that toggle an [IslandSidePanel].
 * - A central [IslandContentCard] occupying the remaining space.
 * - A right [IslandSideBar] + [IslandSidePanel] pair mirroring the left side.
 *
 * Panel visibility is managed with local `remember` state — one nullable
 * [SidePanel] per side. Clicking an already-active icon collapses the panel;
 * clicking a different icon on the same side switches to that panel.
 *
 * @param isDarkTheme Whether dark theme is currently active.
 * @param onToggleTheme Callback invoked when the user toggles the theme.
 * @param modifier Optional [Modifier] applied to the outermost container.
 */
@Composable
fun IslandLayout(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leftPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Left } }
    val rightPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Right } }

    var activeLeftPanel by remember { mutableStateOf<SidePanel?>(null) }
    var activeRightPanel by remember { mutableStateOf<SidePanel?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        IslandToolbar(
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IslandSideBar(
                panels = leftPanels,
                activePanel = activeLeftPanel,
                onPanelToggle = { panel ->
                    activeLeftPanel = if (activeLeftPanel == panel) null else panel
                },
            )

            IslandSidePanel(
                visible = activeLeftPanel != null,
                title = activeLeftPanel?.label ?: "",
                onClose = { activeLeftPanel = null },
                fromEnd = false,
                modifier = Modifier.fillMaxHeight(),
            ) {
                PanelPlaceholderContent(panel = activeLeftPanel)
            }

            IslandContentCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            IslandSidePanel(
                visible = activeRightPanel != null,
                title = activeRightPanel?.label ?: "",
                onClose = { activeRightPanel = null },
                fromEnd = true,
                modifier = Modifier.fillMaxHeight(),
            ) {
                PanelPlaceholderContent(panel = activeRightPanel)
            }

            IslandSideBar(
                panels = rightPanels,
                activePanel = activeRightPanel,
                onPanelToggle = { panel ->
                    activeRightPanel = if (activeRightPanel == panel) null else panel
                },
            )
        }
    }
}

/**
 * Temporary placeholder content rendered inside an [IslandSidePanel].
 *
 * Displays a short description of the panel's purpose. Will be replaced by
 * dedicated per-panel composables (e.g. `SignPanel`, `ValidatePanel`) in the future.
 *
 * @param panel The currently active [SidePanel], or `null` if the panel is collapsing.
 */
@Composable
private fun PanelPlaceholderContent(panel: SidePanel?) {
    when (panel) {
        SidePanel.Signature -> Text(
            text = "Signature details and metadata will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Sign -> Text(
            text = "Signing operations will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Validate -> Text(
            text = "Validation results will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Archive -> Text(
            text = "Archival and re-timestamping controls will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Settings -> Text(
            text = "Application settings will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Help -> Text(
            text = "Help and documentation will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        else -> {}
    }
}

