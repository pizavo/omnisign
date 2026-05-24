package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.platform.platformFilePath
import cz.pizavo.omnisign.ui.platform.readCertificateFileBytes
import cz.pizavo.omnisign.ui.platform.readCertificateFileBytesFromPath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_folder
import omnisign.composeapp.generated.resources.icon_plus
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Reusable section for listing, adding, and removing directly trusted certificates within a scope.
 *
 * Displays the [TrustedCertificate]s currently referenced by the scope, each with a type badge,
 * expiry, and a short fingerprint, followed by an inline form for adding a certificate via a file
 * picker or manual path entry. The form has no name field — a certificate is identified by its
 * fingerprint. The component is agnostic of storage scope; callers wire it into the panel for the
 * global or a profile scope.
 *
 * @param certificates The certificates currently referenced by the scope.
 * @param enabled Whether the add/remove controls are interactive. `false` disables editing
 *   (e.g. on the web target where no trust store backend exists).
 * @param onAdd Called with the picked certificate file bytes, the selected type, and the source
 *   path (the picked file path or the typed path) the certificate was read from.
 * @param onRemove Called with the fingerprint of the certificate to remove.
 * @param addError Human-readable error from the last failed Add attempt, or `null`.
 * @param onClearError Called to clear [addError] when the user starts a new interaction.
 * @param onError Called with a human-readable message when reading a certificate file fails.
 */
@Composable
fun TrustedCertificatesSection(
    certificates: List<TrustedCertificate>,
    enabled: Boolean = true,
    onAdd: (ByteArray, TrustedCertificateType, String) -> Unit,
    onRemove: (String) -> Unit,
    addError: String? = null,
    onClearError: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    if (certificates.isEmpty()) {
        Text(
            text = "No trusted certificates registered.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
    } else {
        certificates.forEachIndexed { index, cert ->
            TrustedCertificateRow(
                certificate = cert,
                enabled = enabled,
                onRemove = { onRemove(cert.fingerprint) },
            )
            if (index < certificates.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    if (enabled) {
        Spacer(modifier = Modifier.height(12.dp))

        TrustedCertificateAddForm(
            onAdd = onAdd,
            error = addError,
            onClearError = onClearError,
            onError = onError,
        )
    }
}

/**
 * Single row displaying a referenced trusted certificate with its type, expiry, and a remove button.
 *
 * @param certificate The certificate to display.
 * @param enabled Whether the remove button is interactive.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun TrustedCertificateRow(
    certificate: TrustedCertificate,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = certificate.subjectDN, style = LumoTheme.typography.label1)
                Chip(
                    label = {
                        Text(
                            text = certificate.type.name,
                            style = LumoTheme.typography.body2,
                        )
                    },
                    selected = false,
                    enabled = false,
                    onClick = {},
                )
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
        IconButton(
            variant = IconButtonVariant.Ghost,
            enabled = enabled,
            onClick = onRemove,
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_x),
                contentDescription = "Remove ${certificate.subjectDN}",
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
            label = { Text(text = "Type") },
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
            label = { Text(text = "Certificate file") },
            placeholder = { Text(text = "/path/to/certificate.pem") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            trailingIcon = {
                TooltipBox(
                    tooltip = { Tooltip { Text(text = "Browse") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        variant = IconButtonVariant.Ghost,
                        onClick = { filePicker.launch() },
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_folder),
                            contentDescription = "Browse for certificate file",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
        TooltipBox(
            tooltip = { Tooltip { Text(text = "Add") } },
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
                    contentDescription = "Add certificate",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (error != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = error,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.error,
        )
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
