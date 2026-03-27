package cz.pizavo.omnisign.ui.model

import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_help
import omnisign.composeapp.generated.resources.icon_profile
import omnisign.composeapp.generated.resources.icon_signature
import org.jetbrains.compose.resources.DrawableResource

/**
 * Identifies which side of the island layout a [SidePanel] is attached to.
 */
enum class PanelSide {
    /** Left-hand side of the layout. */
    Left,

    /** Right-hand side of the layout. */
    Right,
}

/**
 * Lists the tool panels available in the island layout.
 *
 * Each entry carries display metadata and declares which [PanelSide] it belongs to,
 * so the layout can render left and right sidebars independently.
 *
 * @property label Human-readable name shown in the panel header and sidebar tooltip.
 * @property icon Tabler icon drawable resource displayed in the sidebar strip.
 * @property contentDescription Accessibility description for the sidebar icon.
 * @property side The side of the layout this panel is attached to.
 * @property pinToBottom When `true` the icon is pushed to the bottom of its sidebar.
 */
enum class SidePanel(
    val label: String,
    val icon: DrawableResource,
    val contentDescription: String,
    val side: PanelSide,
    val pinToBottom: Boolean = false,
) {
    /** Signature details and metadata panel. */
    Signature(
        label = "Signature",
        icon = Res.drawable.icon_signature,
        contentDescription = "Open signature details panel",
        side = PanelSide.Left,
    ),


    /** Configuration profiles management panel. */
    Profiles(
        label = "Profiles",
        icon = Res.drawable.icon_profile,
        contentDescription = "Open profiles panel",
        side = PanelSide.Right,
    ),
    
    /** Application help and documentation panel. */
    Help(
        label = "Help",
        icon = Res.drawable.icon_help,
        contentDescription = "Open help panel",
        side = PanelSide.Right,
        pinToBottom = true,
    ),
}
