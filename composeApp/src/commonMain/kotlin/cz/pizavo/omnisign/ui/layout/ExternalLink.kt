package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Text
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Inline text link that opens [url] outside the application.
 *
 * Renders [text] as an underlined, primary-colored link followed by a muted
 * external-link glyph that flags the navigation as leaving the app. The link is
 * opened through [LocalUriHandler], so it behaves correctly on both the desktop
 * (JVM) and web (Wasm) targets. Reusable anywhere an outbound web link needs the
 * standard "opens in browser" affordance.
 *
 * @param text The visible link label.
 * @param url Absolute URL opened in the system browser when the link is clicked.
 * @param modifier Optional [Modifier] applied to the link row.
 * @param contentDescription Accessibility description for the external-link icon.
 */
@Composable
fun ExternalLink(
    text: String,
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    val resolvedContentDescription = contentDescription ?: stringResource(Res.string.externallink_content_description)

    Row(
        modifier = modifier.clickable { uriHandler.openUri(url) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.primary,
            textDecoration = TextDecoration.Underline,
        )
        Icon(
            painter = painterResource(Res.drawable.icon_external_link),
            contentDescription = resolvedContentDescription,
            modifier = Modifier.size(14.dp),
            tint = LumoTheme.colors.textSecondary,
        )
    }
}
