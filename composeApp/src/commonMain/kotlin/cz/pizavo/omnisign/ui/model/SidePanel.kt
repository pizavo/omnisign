package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

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
 * so the layout can render left and right sidebars independently. The [label] and
 * [contentDescription] are locale-resolved at composition time.
 *
 * @property icon Tabler icon drawable resource displayed in the sidebar strip.
 * @property side The side of the layout this panel is attached to.
 * @property pinToBottom When `true` the icon is pushed to the bottom of its sidebar.
 */
enum class SidePanel(
    val icon: DrawableResource,
    val side: PanelSide,
    val pinToBottom: Boolean = false,
) {
    /** Signature details and metadata panel. */
    Signature(
        icon = Res.drawable.icon_signature,
        side = PanelSide.Left,
    ),


    /** Configuration profiles management panel. */
    Profiles(
        icon = Res.drawable.icon_profile,
        side = PanelSide.Right,
    ),

    /** Trusted CA and TSA certificates overview panel. */
    TrustedCerts(
        icon = Res.drawable.icon_certificate,
        side = PanelSide.Right,
    ),

    /** Application help and documentation panel. */
    Help(
        icon = Res.drawable.icon_help,
        side = PanelSide.Right,
        pinToBottom = true,
    );

    /** Human-readable name shown in the panel header and sidebar tooltip. */
    @Composable
    fun label(): String = when (this) {
        Signature -> stringResource(Res.string.sidepanel_signature)
        Profiles -> stringResource(Res.string.sidepanel_profiles)
        TrustedCerts -> stringResource(Res.string.sidepanel_trustedcerts)
        Help -> stringResource(Res.string.sidepanel_help)
    }

    /** Accessibility description for the sidebar icon. */
    @Composable
    fun contentDescription(): String = when (this) {
        Signature -> stringResource(Res.string.sidepanel_signature_cd)
        Profiles -> stringResource(Res.string.sidepanel_profiles_cd)
        TrustedCerts -> stringResource(Res.string.sidepanel_trustedcerts_cd)
        Help -> stringResource(Res.string.sidepanel_help_cd)
    }
}
