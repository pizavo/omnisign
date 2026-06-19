package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.SelectableContent
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.TrustedCertsPanelState
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only overview panel for directly trusted certificates in the current context.
 *
 * The app-managed trust store is the source of truth. The panel shows the global scope and,
 * when a profile is active, the active profile scope in separate labeled sections — for viewing
 * only. Adding and removing certificates happens where each scope's configuration lives: the
 * global scope in Settings → Trusted Certificates, and a profile scope in the profile editor.
 *
 * On the web target the trust store has no backend; the panel renders an explanatory message
 * rather than crashing.
 *
 * @param state Current [TrustedCertsPanelState] from [cz.pizavo.omnisign.ui.viewmodel.TrustedCertsViewModel].
 */
@Composable
fun TrustedCertsPanel(state: TrustedCertsPanelState) {
    if (!state.available) {
        EmptyState(message = stringResource(Res.string.msg_trusted_certs_unavailable))
        return
    }

    if (state.error != null) {
        SelectableContent {
            Text(
                text = state.error,
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (state.loading) {
        Text(
            text = stringResource(Res.string.trustedcertspanel_loading),
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        return
    }

    Text(
        text = stringResource(Res.string.trustedcertspanel_view_only_hint),
        style = LumoTheme.typography.body2,
        color = LumoTheme.colors.textSecondary,
    )
    Spacer(modifier = Modifier.height(12.dp))

    val profileName = state.profileName

    if (profileName != null) {
        SectionHeader(label = stringResource(Res.string.trustedcertspanel_section_profile, profileName))
        Spacer(modifier = Modifier.height(6.dp))

        TrustedCertificateList(certificates = state.profileCertificates)

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
    }

    SectionHeader(label = stringResource(Res.string.trustedcertspanel_section_global))
    Spacer(modifier = Modifier.height(6.dp))

    TrustedCertificateList(certificates = state.globalCertificates)
}

/**
 * The section header with a label.
 *
 * @param label Text displayed as the section title.
 */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = LumoTheme.typography.label1,
        color = LumoTheme.colors.text,
    )
}

/**
 * Empty-state message with a certificate icon and descriptive text.
 *
 * @param message The guidance text to display.
 */
@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(Res.drawable.icon_certificate),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = LumoTheme.colors.textSecondary.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    }
}
