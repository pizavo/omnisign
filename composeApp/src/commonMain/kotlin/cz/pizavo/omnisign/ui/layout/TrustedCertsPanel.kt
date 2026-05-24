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
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.TrustedCertsPanelState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_certificate
import org.jetbrains.compose.resources.painterResource

/**
 * Panel for viewing and managing directly trusted certificates for the current context.
 *
 * The app-managed trust store is the source of truth. The panel shows the global scope and,
 * when a profile is active, the profile scope in separate labeled sections. Each section lets
 * the user add a certificate from a file and remove one by its fingerprint.
 *
 * On the web target the trust store has no backend; the panel renders an explanatory message
 * and disables editing rather than crashing.
 *
 * @param state Current [TrustedCertsPanelState] from [cz.pizavo.omnisign.ui.viewmodel.TrustedCertsViewModel].
 * @param onAdd Called with the target scope, picked certificate file bytes, selected type, and the
 *   source path the certificate was read from.
 * @param onRemove Called with the target scope and the fingerprint of the certificate to remove.
 * @param onClearAddError Called to clear the add error when the user starts a new interaction.
 * @param onAddError Called with a human-readable message when reading a certificate file fails.
 */
@Composable
fun TrustedCertsPanel(
    state: TrustedCertsPanelState,
    onAdd: (TrustScope, ByteArray, TrustedCertificateType, String) -> Unit = { _, _, _, _ -> },
    onRemove: (TrustScope, String) -> Unit = { _, _ -> },
    onClearAddError: () -> Unit = {},
    onAddError: (String) -> Unit = {},
) {
    if (!state.available) {
        EmptyState(message = "Managing trusted certificates is not available on this platform.")
        return
    }

    if (state.error != null) {
        Text(
            text = state.error,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (state.loading) {
        Text(
            text = "Loading…",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        return
    }

    val profileName = state.profileName

    if (profileName != null) {
        SectionHeader(label = "Profile — $profileName")
        Spacer(modifier = Modifier.height(6.dp))

        val profileScope = TrustScope.Profile(profileName)
        TrustedCertificatesSection(
            certificates = state.profileCertificates,
            onAdd = { bytes, type, source -> onAdd(profileScope, bytes, type, source) },
            onRemove = { fingerprint -> onRemove(profileScope, fingerprint) },
            addError = state.addError,
            onClearError = onClearAddError,
            onError = onAddError,
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
    }

    SectionHeader(label = "Global")
    Spacer(modifier = Modifier.height(6.dp))

    TrustedCertificatesSection(
        certificates = state.globalCertificates,
        onAdd = { bytes, type, source -> onAdd(TrustScope.Global, bytes, type, source) },
        onRemove = { fingerprint -> onRemove(TrustScope.Global, fingerprint) },
        addError = if (profileName != null) null else state.addError,
        onClearError = onClearAddError,
        onError = onAddError,
    )
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
