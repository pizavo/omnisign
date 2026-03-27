package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.AlertDialog
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.TextField
import cz.pizavo.omnisign.ui.model.ProfileEditState
import cz.pizavo.omnisign.ui.model.ProfileListState
import cz.pizavo.omnisign.ui.model.ProfilePanelMode
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_cancel
import omnisign.composeapp.generated.resources.icon_check
import omnisign.composeapp.generated.resources.icon_delete
import omnisign.composeapp.generated.resources.icon_pencil
import omnisign.composeapp.generated.resources.icon_profile_add
import omnisign.composeapp.generated.resources.icon_profile_deselect
import omnisign.composeapp.generated.resources.icon_profile_off
import omnisign.composeapp.generated.resources.icon_profile_select
import org.jetbrains.compose.resources.painterResource

private val RowIconSize = 18.dp
private val RowButtonSize = 28.dp
private val RowButtonPadding = PaddingValues(2.dp)

/**
 * Panel content displaying configuration profiles.
 *
 * Dispatches between the profile list view and the profile edit form based on
 * [ProfileListState.mode]. In [ProfilePanelMode.Listing] mode, a toolbar row with
 * "Add profile" and "Deselect active profile" buttons is rendered above the profile
 * list. In [ProfilePanelMode.Editing] mode, a [ProfileEditPanel] form is shown.
 *
 * @param state Current [ProfileListState] from [cz.pizavo.omnisign.ui.viewmodel.ProfileViewModel].
 * @param onToggleActive Called when the user clicks the select/deselect icon on a profile row.
 * @param onEdit Called when the user clicks the edit icon; receives the profile name.
 * @param onDelete Called when the user confirms deletion; receives the profile name.
 * @param onAdd Called when the user clicks the add-profile button.
 * @param onDeselectActive Called when the user clicks the deselect-all button.
 * @param onConfirmCreate Called when the user confirms the new profile name.
 * @param onCancelCreate Called when the user cancels the inline creation row.
 * @param onFieldChange Called with a transform to update a single field in the edit form.
 * @param onSaveEdit Called when the user clicks Save in the edit form.
 */
@Composable
fun ProfilesPanel(
    state: ProfileListState,
    onToggleActive: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onDeselectActive: () -> Unit,
    onConfirmCreate: (String) -> Unit,
    onCancelCreate: () -> Unit,
    onFieldChange: ((ProfileEditState) -> ProfileEditState) -> Unit,
    onSaveEdit: () -> Unit,
) {
    when (state.mode) {
        is ProfilePanelMode.Listing -> ProfileListContent(
            state = state,
            onToggleActive = onToggleActive,
            onEdit = onEdit,
            onDelete = onDelete,
            onAdd = onAdd,
            onDeselectActive = onDeselectActive,
            onConfirmCreate = onConfirmCreate,
            onCancelCreate = onCancelCreate,
        )
        is ProfilePanelMode.Editing -> {
            val editState = state.editState
            if (editState != null) {
                ProfileEditPanel(
                    state = editState,
                    onFieldChange = onFieldChange,
                    onSave = onSaveEdit,
                    globalDisabledHashAlgorithms = state.globalDisabledHashAlgorithms,
                    globalDisabledEncryptionAlgorithms = state.globalDisabledEncryptionAlgorithms,
                )
            }
        }
    }
}

/**
 * Profile list content shown when the panel is in [ProfilePanelMode.Listing] mode.
 */
@Composable
private fun ProfileListContent(
    state: ProfileListState,
    onToggleActive: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onDeselectActive: () -> Unit,
    onConfirmCreate: (String) -> Unit,
    onCancelCreate: () -> Unit,
) {
    var pendingDeleteProfile by remember { mutableStateOf<String?>(null) }

    pendingDeleteProfile?.let { profileName ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            onConfirmClick = {
                onDelete(profileName)
                pendingDeleteProfile = null
            },
            title = "Delete profile",
            text = "Are you sure you want to delete the profile \"$profileName\"? This action cannot be undone.",
            confirmButtonText = "Delete",
            dismissButtonText = "Cancel",
            titleContentColor = LumoTheme.colors.error,
        )
    }

    if (state.error != null) {
        Text(
            text = state.error,
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    ProfileToolbar(
        hasActiveProfile = state.activeProfile != null,
        creatingNew = state.creatingNew,
        onAdd = onAdd,
        onDeselectActive = onDeselectActive,
    )

    Spacer(modifier = Modifier.height(4.dp))

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (state.creatingNew) {
            NewProfileRow(
                onConfirm = onConfirmCreate,
                onCancel = onCancelCreate,
            )
            if (state.profiles.isNotEmpty()) {
                HorizontalDivider()
            }
        }

        if (state.profiles.isEmpty() && !state.creatingNew && !state.loading) {
            Text(
                text = "No profiles defined yet.",
                style = LumoTheme.typography.body2,
                color = LumoTheme.colors.textSecondary,
            )
        }

        state.profiles.forEachIndexed { index, profile ->
            ProfileRow(
                profile = profile,
                isActive = profile.name == state.activeProfile,
                onToggleActive = { onToggleActive(profile.name) },
                onEdit = { onEdit(profile.name) },
                onRequestDelete = { pendingDeleteProfile = profile.name },
            )
            if (index < state.profiles.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

/**
 * Toolbar row rendered above the profile list with Add and Deselect actions.
 *
 * @param hasActiveProfile Whether any profile is currently active.
 * @param creatingNew Whether the inline creation row is currently displayed.
 * @param onAdd Called when the add-profile button is clicked.
 * @param onDeselectActive Called when the Deselect button is clicked.
 */
@Composable
private fun ProfileToolbar(
    hasActiveProfile: Boolean,
    creatingNew: Boolean,
    onAdd: () -> Unit,
    onDeselectActive: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipBox(
            tooltip = { Tooltip { Text(text = "Add profile") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                enabled = !creatingNew,
                onClick = onAdd,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_profile_add),
                    contentDescription = "Add profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }

        TooltipBox(
            tooltip = { Tooltip { Text(text = "Deselect active profile") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                enabled = hasActiveProfile,
                onClick = onDeselectActive,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_profile_off),
                    contentDescription = "Deselect active profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }
    }
}

/**
 * Inline row for creating a new profile with a text input and confirm/cancel buttons.
 *
 * The text field is automatically focused when the row appears. The confirm button
 * is disabled when the input is blank. Pressing Enter on the keyboard also confirms.
 *
 * @param onConfirm Called with the entered profile name when the user confirms.
 * @param onCancel Called when the user cancels creation.
 */
@Composable
private fun NewProfileRow(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val isValid = name.isNotBlank()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text(text = "Profile name") },
            isError = name.isNotEmpty() && !isValid,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (isValid) onConfirm(name) }),
        )

        TooltipBox(
            tooltip = { Tooltip { Text(text = "Confirm") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                enabled = isValid,
                onClick = { onConfirm(name) },
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_check),
                    contentDescription = "Confirm new profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }

        TooltipBox(
            tooltip = { Tooltip { Text(text = "Cancel") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                onClick = onCancel,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_cancel),
                    contentDescription = "Cancel new profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }
    }
}

/**
 * A single profile row displaying the name, optional description, and action icons.
 *
 * @param profile The [ProfileConfig] to render.
 * @param isActive Whether this profile is the currently active one.
 * @param onToggleActive Called when the select/deselect icon is clicked.
 * @param onEdit Called when the edit icon is clicked.
 * @param onRequestDelete Called when the delete icon is clicked to request confirmation.
 */
@Composable
private fun ProfileRow(
    profile: ProfileConfig,
    isActive: Boolean,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = LumoTheme.typography.body1,
                color = if (isActive) LumoTheme.colors.primary else LumoTheme.colors.text,
            )
            val description = profile.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = LumoTheme.typography.body2,
                    color = LumoTheme.colors.textSecondary,
                )
            }
        }

        val selectLabel = if (isActive) "Deselect profile" else "Select profile"
        val selectIcon = if (isActive)
            painterResource(Res.drawable.icon_profile_deselect)
        else
            painterResource(Res.drawable.icon_profile_select)

        TooltipBox(
            tooltip = { Tooltip { Text(text = selectLabel) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                onClick = onToggleActive,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = selectIcon,
                    contentDescription = selectLabel,
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }

        TooltipBox(
            tooltip = { Tooltip { Text(text = "Edit profile") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.Ghost,
                onClick = onEdit,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_pencil),
                    contentDescription = "Edit profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }

        TooltipBox(
            tooltip = { Tooltip { Text(text = "Delete profile") } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                modifier = Modifier.defaultMinSize(
                    minWidth = RowButtonSize,
                    minHeight = RowButtonSize,
                ),
                variant = IconButtonVariant.DestructiveGhost,
                onClick = onRequestDelete,
                contentPadding = RowButtonPadding,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.icon_delete),
                    contentDescription = "Delete profile",
                    modifier = Modifier.size(RowIconSize),
                )
            }
        }
    }
}
