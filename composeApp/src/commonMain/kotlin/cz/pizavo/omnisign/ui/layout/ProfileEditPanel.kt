package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Switch
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.TriStateToggle
import cz.pizavo.omnisign.lumo.components.TriToggleState
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.ProfileEditState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_eye
import omnisign.composeapp.generated.resources.icon_eye_off
import org.jetbrains.compose.resources.painterResource

/**
 * Edit form rendered inside the Profiles side panel when the user is editing a profile.
 *
 * The form is organized into sections separated by dividers:
 * 1. Profile name (read-only header)
 * 2. Description text field
 * 3. Algorithm selectors (dropdowns) and timestamp level overrides (tri-state toggles)
 * 4. Timestamp server toggle and fields
 * 5. Disabled algorithm chip selectors
 * 6. Save button
 *
 * @param state The current [ProfileEditState] holding all form field values.
 * @param onFieldChange Callback accepting a transform function to update a single field.
 * @param onSave Called when the user clicks the Save button.
 * @param hasChanges Whether any persistable field differs from the originally loaded state.
 * @param globalDisabledHashAlgorithms Hash algorithms disabled at the global level.
 *   These appear greyed-out in the algorithm selector and are always shown as
 *   disabled in the chip toggles.
 * @param globalDisabledEncryptionAlgorithms Encryption algorithms disabled at the global level.
 *   Same behavior as [globalDisabledHashAlgorithms].
 * @param globalAddArchivalTimestamp Whether the global config includes an archival timestamp (B-LTA).
 *   When `true` and the profile's archival toggle is INHERIT, the signature timestamp
 *   toggle is forced to `ENABLED` and disabled.
 */
@Composable
fun ProfileEditPanel(
    state: ProfileEditState,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
    onSave: () -> Unit,
    hasChanges: Boolean = true,
    globalDisabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
    globalDisabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
    globalAddArchivalTimestamp: Boolean = false,
) {
    if (state.error != null) {
        Text(
            text = state.error,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Text(
        text = state.profileName,
        style = LumoTheme.typography.h4,
        color = LumoTheme.colors.text,
    )

    Spacer(modifier = Modifier.height(12.dp))

    DescriptionSection(state = state, onFieldChange = onFieldChange)

    SectionDivider()

    AlgorithmSection(
        state = state,
        onFieldChange = onFieldChange,
        globalDisabledHashAlgorithms = globalDisabledHashAlgorithms,
        globalDisabledEncryptionAlgorithms = globalDisabledEncryptionAlgorithms,
        globalAddArchivalTimestamp = globalAddArchivalTimestamp,
    )

    SectionDivider()

    TimestampSection(state = state, onFieldChange = onFieldChange)

    SectionDivider()

    DisabledAlgorithmsSection(
        state = state,
        onFieldChange = onFieldChange,
        globalDisabledHashAlgorithms = globalDisabledHashAlgorithms,
        globalDisabledEncryptionAlgorithms = globalDisabledEncryptionAlgorithms,
    )

    SectionDivider()

    Text(text = "Trusted Certificates", style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Certificates added here apply only to this profile, in addition to global ones.",
        style = LumoTheme.typography.body2,
        color = LumoTheme.colors.textSecondary,
    )
    Spacer(modifier = Modifier.height(8.dp))

    TrustedCertificatesSection(
        certificates = state.trustedCertificates,
        onAdd = { cert ->
            onFieldChange {
                it.copy(trustedCertificates = it.trustedCertificates.filter { c -> c.name != cert.name } + cert)
            }
        },
        onRemove = { index ->
            onFieldChange {
                it.copy(trustedCertificates = it.trustedCertificates.toMutableList().apply { removeAt(index) })
            }
        },
        addError = state.certAddError,
        onClearError = { onFieldChange { it.copy(certAddError = null) } },
        onError = { message -> onFieldChange { it.copy(certAddError = message) } },
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        text = "Save",
        variant = ButtonVariant.Primary,
        enabled = hasChanges && !state.saving,
        loading = state.saving,
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * Description textarea section.
 */
@Composable
private fun DescriptionSection(
    state: ProfileEditState,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
) {
    UnderlinedTextField(
        value = state.description,
        onValueChange = { value -> onFieldChange { it.copy(description = value) } },
        label = { Text(text = "Description") },
        placeholder = { Text(text = "Optional profile description") },
        singleLine = false,
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Hash algorithm, encryption algorithm selectors, and timestamp level overrides.
 *
 * Algorithms that are disabled at the global level are shown as greyed-out
 * in the dropdown and cannot be selected. Timestamp overrides use a tri-state
 * toggle (Disable / Inherit / Enable).
 *
 * The signature timestamp toggle is forced to `ENABLED` and disabled whenever
 * archival timestamps are effectively active — either because the profile
 * explicitly sets archival to ENABLED, or because it inherits B-LTA from global.
 */
@Composable
private fun AlgorithmSection(
    state: ProfileEditState,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
    globalDisabledHashAlgorithms: Set<HashAlgorithm>,
    globalDisabledEncryptionAlgorithms: Set<EncryptionAlgorithm>,
    globalAddArchivalTimestamp: Boolean,
) {
    val archivalEffectivelyEnabled =
        state.archivalTimestampOverride == TriToggleState.ENABLED ||
                (state.archivalTimestampOverride == TriToggleState.INHERIT && globalAddArchivalTimestamp)

    Text(text = "Algorithms & Timestamps", style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(8.dp))

    DropdownSelector(
        selected = state.hashAlgorithm,
        options = HashAlgorithm.entries.toList(),
        onSelect = { value -> onFieldChange { it.copy(hashAlgorithm = value) } },
        label = { Text(text = "Hash algorithm") },
        disabledOptions = globalDisabledHashAlgorithms,
        itemLabel = { it.name },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    DropdownSelector(
        selected = state.encryptionAlgorithm,
        options = EncryptionAlgorithm.entries.toList(),
        onSelect = { value -> onFieldChange { it.copy(encryptionAlgorithm = value) } },
        label = { Text(text = "Encryption algorithm") },
        disabledOptions = globalDisabledEncryptionAlgorithms,
        itemLabel = { it.name },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(text = "Timestamp level overrides", style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Signature timestamp", style = LumoTheme.typography.body2)
            InfoTooltip(text = "Produces PAdES BASELINE B-LT")
        }
        if (archivalEffectivelyEnabled) {
            val reason = if (state.archivalTimestampOverride == TriToggleState.ENABLED) {
                "Required by this profile's archival timestamp (B-LTA)"
            } else {
                "Required by global archival timestamp setting (B-LTA)"
            }
            TooltipBox(
                tooltip = { Tooltip { Text(text = reason) } },
                state = rememberTooltipState(),
            ) {
                TriStateToggle(
                    state = TriToggleState.ENABLED,
                    onStateChange = {},
                    enabled = false,
                )
            }
        } else {
            TriStateToggle(
                state = state.signatureTimestampOverride,
                onStateChange = { value -> onFieldChange { it.copy(signatureTimestampOverride = value) } },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Archival timestamp", style = LumoTheme.typography.body2)
            InfoTooltip(text = "Produces PAdES BASELINE B-LTA")
        }
        TriStateToggle(
            state = state.archivalTimestampOverride,
            onStateChange = { value ->
                onFieldChange {
                    if (value == TriToggleState.ENABLED) {
                        it.copy(
                            archivalTimestampOverride = value,
                            signatureTimestampOverride = TriToggleState.ENABLED,
                        )
                    } else {
                        it.copy(archivalTimestampOverride = value)
                    }
                }
            },
        )
    }
}

/**
 * Timestamp server toggle switch and configuration fields.
 */
@Composable
private fun TimestampSection(
    state: ProfileEditState,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Timestamp server", style = LumoTheme.typography.label1)
        Switch(
            checked = state.timestampEnabled,
            onCheckedChange = { value -> onFieldChange { it.copy(timestampEnabled = value) } },
        )
    }

    if (state.timestampEnabled) {
        Spacer(modifier = Modifier.height(8.dp))

        UnderlinedTextField(
            value = state.timestampUrl,
            onValueChange = { value -> onFieldChange { it.copy(timestampUrl = value) } },
            label = { Text(text = "URL") },
            placeholder = { Text(text = "https://tsa.example.com/tsr") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        UnderlinedTextField(
            value = state.timestampUsername,
            onValueChange = { value -> onFieldChange { it.copy(timestampUsername = value) } },
            label = { Text(text = "Username") },
            placeholder = { Text(text = "Optional") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        PasswordField(
            value = state.timestampPassword,
            onValueChange = { value -> onFieldChange { it.copy(timestampPassword = value) } },
            hasStoredPassword = state.hasStoredPassword,
        )

        Spacer(modifier = Modifier.height(8.dp))

        UnderlinedTextField(
            value = state.timestampTimeout,
            onValueChange = { value ->
                if (value.all { c -> c.isDigit() }) {
                    onFieldChange { it.copy(timestampTimeout = value) }
                }
            },
            label = { Text(text = "Timeout (ms)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Chip-based toggles for disabling specific hash and encryption algorithms.
 *
 * Algorithms already disabled at the global level are shown as selected and
 * non-interactive (greyed-out) because they cannot be re-enabled from a profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisabledAlgorithmsSection(
    state: ProfileEditState,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
    globalDisabledHashAlgorithms: Set<HashAlgorithm>,
    globalDisabledEncryptionAlgorithms: Set<EncryptionAlgorithm>,
) {
    Text(text = "Disabled hash algorithms", style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(4.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HashAlgorithm.entries.forEach { algo ->
            val globallyDisabled = algo in globalDisabledHashAlgorithms
            val disabled = globallyDisabled || algo in state.disabledHashAlgorithms
            Chip(
                label = { Text(text = algo.name, style = LumoTheme.typography.body2) },
                selected = disabled,
                enabled = !globallyDisabled,
                onClick = {
                    onFieldChange {
                        val updated = if (disabled) {
                            it.disabledHashAlgorithms - algo
                        } else {
                            it.disabledHashAlgorithms + algo
                        }
                        it.copy(disabledHashAlgorithms = updated)
                    }
                },
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(text = "Disabled encryption algorithms", style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(4.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EncryptionAlgorithm.entries.forEach { algo ->
            val globallyDisabled = algo in globalDisabledEncryptionAlgorithms
            val disabled = globallyDisabled || algo in state.disabledEncryptionAlgorithms
            Chip(
                label = { Text(text = algo.name, style = LumoTheme.typography.body2) },
                selected = disabled,
                enabled = !globallyDisabled,
                onClick = {
                    onFieldChange {
                        val updated = if (disabled) {
                            it.disabledEncryptionAlgorithms - algo
                        } else {
                            it.disabledEncryptionAlgorithms + algo
                        }
                        it.copy(disabledEncryptionAlgorithms = updated)
                    }
                },
            )
        }
    }
}

/**
 * Password text field with a trailing visibility toggle icon.
 *
 * When [hasStoredPassword] is true and the field is empty, a dot placeholder indicates
 * that a password is already stored in the OS credential store. Entering a new
 * value will replace the stored password on save.
 *
 * @param value The current password text.
 * @param onValueChange Called when the password text changes.
 * @param hasStoredPassword Whether a password is already persisted in the credential store.
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    hasStoredPassword: Boolean,
) {
    var visible by remember { mutableStateOf(false) }

    UnderlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = "Password") },
        placeholder = {
            Text(
                text = if (hasStoredPassword) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                else "Optional",
            )
        },
        supportingText = if (hasStoredPassword && value.isEmpty()) {
            { Text(text = "Password stored — enter a new value to replace") }
        } else {
            null
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(
                variant = IconButtonVariant.Ghost,
                onClick = { visible = !visible },
            ) {
                Icon(
                    painter = painterResource(
                        if (visible) Res.drawable.icon_eye_off else Res.drawable.icon_eye
                    ),
                    contentDescription = if (visible) "Hide password" else "Show password",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/**
 * Horizontal divider with standard vertical spacing for form sections.
 */
@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Spacer(modifier = Modifier.height(4.dp))
}
