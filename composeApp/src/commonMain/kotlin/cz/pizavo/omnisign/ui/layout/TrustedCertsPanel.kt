package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.TrustedCertsPanelState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_certificate
import org.jetbrains.compose.resources.painterResource

/**
 * Read-only panel showing all trusted certificates effective for the current context.
 *
 * Displays profile-scoped certificates first (when an active profile exists), followed
 * by global certificates, separated by labeled section headers. This gives the user a
 * quick overview of which CAs and TSAs are trusted without navigating into Settings or
 * a profile editor.
 *
 * @param state Current [TrustedCertsPanelState] from [cz.pizavo.omnisign.ui.viewmodel.TrustedCertsViewModel].
 */
@Composable
fun TrustedCertsPanel(state: TrustedCertsPanelState) {
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

    val hasProfile = state.profileName != null
    val totalCount = state.profileCertificates.size + state.globalCertificates.size

    if (totalCount == 0 && !hasProfile) {
        EmptyState(message = "No trusted certificates configured. Add them in Settings → Validation → Trusted Certificates.")
        return
    }

    if (hasProfile) {
        SectionHeader(label = "Profile — ${state.profileName}")
        Spacer(modifier = Modifier.height(6.dp))

        if (state.profileCertificates.isEmpty()) {
            Text(
                text = "No certificates in this profile.",
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        } else {
            state.profileCertificates.forEachIndexed { index, cert ->
                CertificateRow(cert)
                if (index < state.profileCertificates.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
    }

    SectionHeader(label = "Global")
    Spacer(modifier = Modifier.height(6.dp))

    if (state.globalCertificates.isEmpty()) {
        Text(
            text = "No global certificates configured.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    } else {
        state.globalCertificates.forEachIndexed { index, cert ->
            CertificateRow(cert)
            if (index < state.globalCertificates.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
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
 * Single read-only row displaying a trusted certificate's name, type badge, and subject DN.
 *
 * @param cert The certificate entry to display.
 */
@Composable
private fun CertificateRow(cert: TrustedCertificateConfig) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.icon_certificate),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = LumoTheme.colors.textSecondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = cert.name, style = LumoTheme.typography.label1)
                Chip(
                    label = {
                        Text(
                            text = cert.type.name,
                            style = LumoTheme.typography.body2,
                        )
                    },
                    selected = false,
                    enabled = false,
                    onClick = {},
                )
            }
            Text(
                text = cert.subjectDN,
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        }
    }
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

