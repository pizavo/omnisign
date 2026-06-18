package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.SelectableContent
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.PendingTrustedCert
import cz.pizavo.omnisign.ui.platform.platformFilePath
import cz.pizavo.omnisign.ui.platform.readCertificateFileBytes
import cz.pizavo.omnisign.ui.platform.readCertificateFileBytesFromPath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only list of directly trusted certificates for the overview panel.
 *
 * Renders each [TrustedCertificate] with a type badge, expiry, and a short fingerprint, without
 * any add or remove affordances. Used by the Trusted Certificates side panel, which is a view-only
 * overview; mutation happens in the Settings dialog (global scope) and the profile editor.
 *
 * @param certificates The certificates currently referenced by the scope.
 */
@Composable
fun TrustedCertificateList(certificates: List<TrustedCertificate>) {
    if (certificates.isEmpty()) {
        Text(
            text = stringResource(Res.string.trustedcerts_empty),
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        return
    }
    certificates.forEachIndexed { index, cert ->
        TrustedCertificateRow(certificate = cert)
        if (index < certificates.lastIndex) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Reusable editor section for staging additions and removals of directly trusted certificates within a scope.
 *
 * Displays the baseline [certificates] currently in the scope (minus any staged for removal), then the
 * staged [pendingAdditions], followed by an inline form for adding a certificate via a file picker or
 * manual path entry. Removing a baseline certificate or unstaging a pending one only changes the staged
 * set; nothing is written to the trust store until the host form is saved, so Cancel discards an
 * accidental removal. The component is agnostic of storage scope; callers wire it into the Settings
 * dialog for the global scope or the profile editor for a profile scope.
 *
 * Certificates staged for removal stay visible, marked "Removing" with an undo button, so the user
 * can review exactly which certificate they are about to remove before saving — mirroring how a
 * staged addition is marked "Pending".
 *
 * @param certificates Baseline certificates in the scope (the snapshot loaded when editing began).
 * @param pendingAdditions Certificates staged for addition but not yet written.
 * @param pendingRemovals Fingerprints of baseline certificates staged for removal.
 * @param onStageAddition Called with the picked certificate file bytes, the selected type, and the
 *   source path (the picked file path or the typed path) to stage an addition.
 * @param onStageRemoval Called with the fingerprint of a baseline certificate to stage its removal.
 * @param onUnstageRemoval Called with the fingerprint of a certificate whose staged removal is undone.
 * @param onUnstageAddition Called with the index (into [pendingAdditions]) of a staged addition to drop.
 * @param addError Human-readable error from the last failed Add attempt, or `null`.
 * @param onClearError Called to clear [addError] when the user starts a new interaction.
 * @param onError Called with a human-readable message when reading a certificate file fails.
 */
@Composable
fun TrustedCertificatesSection(
    certificates: List<TrustedCertificate>,
    pendingAdditions: List<PendingTrustedCert>,
    pendingRemovals: Set<String>,
    onStageAddition: (ByteArray, TrustedCertificateType, String) -> Unit,
    onStageRemoval: (String) -> Unit,
    onUnstageRemoval: (String) -> Unit,
    onUnstageAddition: (Int) -> Unit,
    addError: String? = null,
    onClearError: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    if (certificates.isEmpty() && pendingAdditions.isEmpty()) {
        Text(
            text = stringResource(Res.string.trustedcerts_empty),
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    } else {
        certificates.forEach { cert ->
            val markedForRemoval = cert.fingerprint in pendingRemovals
            TrustedCertificateRow(
                certificate = cert,
                markedForRemoval = markedForRemoval,
                onAction = {
                    if (markedForRemoval) onUnstageRemoval(cert.fingerprint)
                    else onStageRemoval(cert.fingerprint)
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        pendingAdditions.forEachIndexed { index, pending ->
            PendingTrustedCertRow(
                pending = pending,
                onRemove = { onUnstageAddition(index) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    TrustedCertificateAddForm(
        onAdd = onStageAddition,
        error = addError,
        onClearError = onClearError,
        onError = onError,
    )
}

/**
 * Single row displaying a referenced trusted certificate with its type, expiry, and an optional
 * action button.
 *
 * When [markedForRemoval] is true the row carries a "Removing" badge and its action button becomes
 * an undo affordance, so a staged removal stays visible (with its full details) until saved — the
 * user can review exactly what they are removing and reverse it. When [onAction] is `null` the row
 * is read-only (no button), as in the overview panel.
 *
 * @param certificate The certificate to display.
 * @param markedForRemoval Whether this certificate is staged for removal.
 * @param onAction Callback invoked when the user clicks the action button: it stages a removal for a
 *   normal row, or undoes the staged removal when [markedForRemoval] is true. `null` renders the row
 *   read-only.
 */
@Composable
private fun TrustedCertificateRow(
    certificate: TrustedCertificate,
    markedForRemoval: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = certificate.subjectDN, style = LumoTheme.typography.label1)
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBadge(text = certificate.type.name, color = LumoTheme.colors.tertiary)
                if (markedForRemoval) {
                    StatusBadge(text = stringResource(Res.string.trustedcerts_badge_removing), color = LumoTheme.colors.error)
                }
            }
            Text(
                text = "Expires ${certificate.notAfter.formatDateTime()}",
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
            Text(
                text = shortFingerprint(certificate.fingerprint),
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        }
        if (onAction != null) {
            IconButton(
                variant = IconButtonVariant.Ghost,
                onClick = onAction,
            ) {
                Icon(
                    painter = painterResource(
                        if (markedForRemoval) Res.drawable.icon_arrow_left else Res.drawable.icon_x,
                    ),
                    contentDescription = if (markedForRemoval) {
                        "Undo removal of ${certificate.subjectDN}"
                    } else {
                        "Remove ${certificate.subjectDN}"
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Single row for a certificate staged for addition but not yet saved.
 *
 * Shows the source path and the chosen trust type with a "Pending" badge, and a remove button that
 * drops the staged addition. Parsed details (subject, expiry, fingerprint) are not shown because the
 * certificate is only parsed when the addition is applied to the store on save.
 *
 * @param pending The staged addition to display.
 * @param onRemove Callback invoked when the user drops this staged addition.
 */
@Composable
private fun PendingTrustedCertRow(
    pending: PendingTrustedCert,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = pending.subjectDN, style = LumoTheme.typography.label1)
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBadge(text = pending.type.name, color = LumoTheme.colors.tertiary)
                StatusBadge(text = stringResource(Res.string.trustedcerts_badge_pending), color = LumoTheme.colors.success)
            }
            Text(
                text = "Expires ${pending.notAfter.formatDateTime()}",
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
            Text(
                text = shortFingerprint(pending.fingerprint),
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        }
        IconButton(
            variant = IconButtonVariant.Ghost,
            onClick = onRemove,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_x),
                contentDescription = "Drop staged certificate ${pending.subjectDN}",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Inline form for adding a trusted certificate from a file.
 *
 * Provides a type selector, a certificate file path field with a browse button, and an "Add"
 * icon button. The user can either pick a file via the dialog or type a path manually. On adding,
 * the file bytes are read via [readCertificateFileBytes] or [readCertificateFileBytesFromPath] and
 * passed to [onAdd] along with the source path. If reading fails, [onError] is called with a
 * human-readable message.
 *
 * @param onAdd Called with the certificate file bytes, the selected type, and the source path on success.
 * @param error Human-readable error message from the last failed attempt, or `null`.
 * @param onClearError Called to clear [error] when the user starts a new interaction.
 * @param onError Called with a human-readable message when reading a certificate file fails.
 */
@Composable
private fun TrustedCertificateAddForm(
    onAdd: (ByteArray, TrustedCertificateType, String) -> Unit,
    error: String? = null,
    onClearError: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var type by remember { mutableStateOf(TrustedCertificateType.ANY) }
    var selectedFile by remember { mutableStateOf<PlatformFile?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pem", "der", "crt", "cer")),
    ) { file: PlatformFile? ->
        if (file != null) {
            selectedFile = file
            selectedFileName = platformFilePath(file) ?: file.name
            onClearError()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        DropdownSelector(
            selected = type,
            options = TrustedCertificateType.entries.toList(),
            onSelect = { value -> type = value ?: TrustedCertificateType.ANY },
            label = { Text(text = stringResource(Res.string.trustedcerts_label_type)) },
            showNullOption = false,
            itemLabel = { it.name },
            modifier = Modifier.width(120.dp),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        UnderlinedTextField(
            value = selectedFileName,
            onValueChange = {
                selectedFileName = it
                selectedFile = null
                onClearError()
            },
            label = { Text(text = stringResource(Res.string.trustedcerts_label_certificate_file)) },
            placeholder = { Text(text = stringResource(Res.string.trustedcerts_placeholder_certificate_file)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            trailingIcon = {
                TooltipBox(
                    tooltip = { Tooltip { Text(text = stringResource(Res.string.action_browse)) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        variant = IconButtonVariant.Ghost,
                        onClick = { filePicker.launch() },
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_folder),
                            contentDescription = stringResource(Res.string.trustedcerts_cd_browse),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
        TooltipBox(
            tooltip = { Tooltip { Text(text = stringResource(Res.string.action_add)) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                variant = IconButtonVariant.SuccessOutlined,
                enabled = selectedFileName.isNotBlank(),
                onClick = {
                    onClearError()
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        val bytes = selectedFile?.let { readCertificateFileBytes(it) }
                            ?: readCertificateFileBytesFromPath(selectedFileName.trim())
                        if (bytes != null) {
                            onAdd(bytes, type, selectedFileName.trim())
                            selectedFile = null
                            selectedFileName = ""
                            type = TrustedCertificateType.ANY
                        }
                    } catch (e: Exception) {
                        val detail = e.message ?: e::class.simpleName ?: "Unknown error"
                        onError("Failed to read certificate: $detail")
                    }
                },
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_plus),
                    contentDescription = stringResource(Res.string.trustedcerts_cd_add),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (error != null) {
        Spacer(modifier = Modifier.height(4.dp))
        SelectableContent {
            Text(
                text = error,
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.error,
            )
        }
    }
}

/**
 * A small rounded badge rendered as a tinted, outlined pill in the given accent [color].
 *
 * Used for the certificate type (blue "info" accent), a staged addition (green "Pending"), and a
 * staged removal (red "Removing"), so each reads as an intentional label rather than a disabled chip.
 *
 * @param text The badge label.
 * @param color The accent color used for the text, the faint background tint, and the outline.
 */
@Composable
private fun StatusBadge(text: String, color: Color) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.18f))
            .border(width = 1.dp, color = color.copy(alpha = 0.5f), shape = shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = LumoTheme.typography.body2, color = color)
    }
}

/**
 * Shorten an algorithm-prefixed SHA-256 fingerprint for compact display.
 *
 * Keeps the algorithm prefix and the first and last few hex characters, eliding the middle,
 * e.g. `sha256-1a2b3c…7d8e9f`. Short fingerprints are returned unchanged.
 *
 * @param fingerprint The full algorithm-prefixed fingerprint.
 * @return A compact, human-readable rendering of [fingerprint].
 */
private fun shortFingerprint(fingerprint: String): String {
    val dash = fingerprint.indexOf('-')
    if (dash < 0) return fingerprint
    val prefix = fingerprint.substring(0, dash + 1)
    val hex = fingerprint.substring(dash + 1)
    return if (hex.length <= 16) fingerprint else "$prefix${hex.take(6)}…${hex.takeLast(6)}"
}
