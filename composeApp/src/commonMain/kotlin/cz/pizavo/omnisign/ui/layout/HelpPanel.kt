package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.BuildConfig
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.TooltipPlacement
import cz.pizavo.omnisign.lumo.components.rememberTooltipPositionProvider
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_file_text
import omnisign.composeapp.generated.resources.icon_github
import omnisign.composeapp.generated.resources.icon_scale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Public source repository the GitHub action button opens. */
private const val HelpRepositoryUrl = "https://github.com/pizavo/omnisign"

/** Hosted Docusaurus documentation site the Documentation button opens. */
private const val HelpDocumentationUrl = "https://pizavo.github.io/omnisign/"

/** Canonical text of the license under which OmniSign is distributed. */
private const val HelpLicenseUrl = "https://www.gnu.org/licenses/agpl-3.0.html"

/** Human-readable name of the project license, mirrored from `LICENSE.md`. */
private const val HelpLicenseName = "GNU AGPL v3.0 or later"

/** Copyright/author line, kept in sync with `LICENSE.md` and the packaging metadata. */
private const val HelpAuthorLine = "© 2026 Pizavo"

/**
 * Help panel body rendered inside the right-hand [IslandSidePanel].
 *
 * Shows the source repository and online documentation as icon buttons at the
 * top, and pins the license, build version and author to the bottom of the
 * panel. External links are opened through [LocalUriHandler] so the panel works
 * unchanged on both the desktop (JVM) and web (Wasm) targets.
 *
 * Expects the host [IslandSidePanel] to be rendered with `scrollable = false`
 * so the root [Column] can claim the full panel height and anchor that group.
 */
@Composable
fun HelpPanel() {
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HelpActionButton(
                label = "GitHub Repository",
                icon = Res.drawable.icon_github,
                contentDescription = "Open the GitHub repository",
                onClick = { uriHandler.openUri(HelpRepositoryUrl) },
            )
            HelpActionButton(
                label = "Documentation",
                icon = Res.drawable.icon_file_text,
                contentDescription = "Open the online documentation",
                onClick = { uriHandler.openUri(HelpDocumentationUrl) },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_scale),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LumoTheme.colors.textSecondary,
            )
            Text(
                text = "License",
                style = LumoTheme.typography.label1,
                modifier = Modifier.weight(1f),
            )
            ExternalLink(
                text = HelpLicenseName,
                url = HelpLicenseUrl,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "v${BuildConfig.VERSION}",
                style = LumoTheme.typography.body3,
                color = LumoTheme.colors.textSecondary,
            )
            Text(
                text = HelpAuthorLine,
                style = LumoTheme.typography.body3,
                color = LumoTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Icon-only outlined button used for the Help panel's external-link actions
 * (repository, documentation). The [label] is surfaced as a hover tooltip
 * instead of inline text, mirroring the sidebar icon affordance.
 *
 * @param label Tooltip text shown on hover.
 * @param icon Drawable resource rendered inside the button.
 * @param contentDescription Accessibility description for [icon].
 * @param onClick Invoked when the button is pressed.
 */
@Composable
private fun HelpActionButton(
    label: String,
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipPlacement.Top),
        tooltip = { Tooltip { Text(text = label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            variant = IconButtonVariant.PrimaryOutlined,
            onClick = onClick,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
