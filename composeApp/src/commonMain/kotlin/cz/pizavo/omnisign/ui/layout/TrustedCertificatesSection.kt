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
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
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
import cz.pizavo.omnisign.ui.platform.readCertificateFile
import cz.pizavo.omnisign.ui.platform.readCertificateFileFromPath
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
 * Reusable section for listing, adding, and removing directly trusted certificates.
 *
 * Displays a list of existing [TrustedCertificateConfig] entries with type badges and
 * a remove button, followed by an inline form for adding new certificates via a file
 * picker or manual path entry. The component is agnostic of storage scope — callers
 * wire it into the global settings dialog or a profile edit panel.
 *
 * @param certificates The current list of trusted certificates to display.
 * @param onAdd Called with a newly parsed [TrustedCertificateConfig] to append.
 * @param onRemove Called with the index of the certificate to remove.
 * @param addError Human-readable error from the last failed Add attempt, or `null`.
 * @param onClearError Called to clear [addError] when the user starts a new interaction.
 * @param onError Called with a human-readable message when adding a certificate fails.
 */
@Composable
fun TrustedCertificatesSection(
    certificates: List<TrustedCertificateConfig>,
    onAdd: (TrustedCertificateConfig) -> Unit,
    onRemove: (Int) -> Unit,
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
                onRemove = { onRemove(index) },
            )
            if (index < certificates.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    TrustedCertificateAddForm(
        onAdd = onAdd,
        error = addError,
        onClearError = onClearError,
        onError = onError,
    )
}

/**
 * Single row displaying a registered trusted certificate with its type and a remove button.
 *
 * @param certificate The certificate entry to display.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun TrustedCertificateRow(
    certificate: TrustedCertificateConfig,
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
                Text(text = certificate.name, style = LumoTheme.typography.label1)
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
                text = certificate.subjectDN,
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
                contentDescription = "Remove ${certificate.name}",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Inline form for adding a new trusted certificate.
 *
 * Provides a name field, a type selector, a certificate file path field with a browse
 * button, and an "Add" icon button. The user can either pick a file via the dialog or
 * type a path manually. On adding, the file is parsed via [readCertificateFile] or
 * [readCertificateFileFromPath] and the result is passed to [onAdd]. If parsing fails,
 * [onError] is called with a human-readable message.
 *
 * @param onAdd Called with the parsed [TrustedCertificateConfig] on success.
 * @param error Human-readable error message from the last failed attempt, or `null`.
 * @param onClearError Called to clear [error] when the user starts a new interaction.
 * @param onError Called with a human-readable message when adding a certificate fails.
 */
@Composable
private fun TrustedCertificateAddForm(
    onAdd: (TrustedCertificateConfig) -> Unit,
    error: String? = null,
    onClearError: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
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
        UnderlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onClearError()
            },
            label = { Text(text = "Name") },
            placeholder = { Text(text = "Label") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
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
                enabled = name.isNotBlank() && selectedFileName.isNotBlank(),
                onClick = {
                    onClearError()
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        val config = selectedFile?.let { readCertificateFile(name.trim(), it, type) }
                            ?: readCertificateFileFromPath(name.trim(), selectedFileName.trim(), type)
                        if (config != null) {
                            onAdd(config)
                            name = ""
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





