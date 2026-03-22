package cz.pizavo.omnisign.ui.model

import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_archive
import omnisign.composeapp.generated.resources.icon_help
import omnisign.composeapp.generated.resources.icon_pencil
import omnisign.composeapp.generated.resources.icon_settings
import omnisign.composeapp.generated.resources.icon_shield_check
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
 * Enumerates the tool panels available in the island layout.
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
    /** Document signing operations panel. */
    Sign(
        label = "Sign",
        icon = Res.drawable.icon_pencil,
        contentDescription = "Open signing panel",
        side = PanelSide.Left,
    ),

    /** Signature details and metadata panel. */
    Signature(
        label = "Signature",
        icon = Res.drawable.icon_signature,
        contentDescription = "Open signature details panel",
        side = PanelSide.Left,
    ),

    /** Signature and document validation panel. */
    Validate(
        label = "Validate",
        icon = Res.drawable.icon_shield_check,
        contentDescription = "Open validation panel",
        side = PanelSide.Left,
    ),

    /** Re-timestamping and archival (B-LTA) panel. */
    Archive(
        label = "Archive",
        icon = Res.drawable.icon_archive,
        contentDescription = "Open archive panel",
        side = PanelSide.Left,
    ),

    /** Application settings panel. */
    Settings(
        label = "Settings",
        icon = Res.drawable.icon_settings,
        contentDescription = "Open settings panel",
        side = PanelSide.Right,
        pinToBottom = true,
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
