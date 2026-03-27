package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.ui.model.ProfileEditState
import cz.pizavo.omnisign.ui.model.ProfileListState
import cz.pizavo.omnisign.ui.model.ProfilePanelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the profile list panel state.
 *
 * Loads profiles via [ManageProfileUseCase], tracks the currently active profile,
 * and exposes actions for selecting, deselecting, deleting, creating, and editing profiles.
 *
 * @param manageProfileUseCase Use case for CRUD operations on configuration profiles.
 * @param getConfigUseCase Use case for reading the current application configuration.
 * @param credentialStore Optional OS credential store for persisting TSA passwords.
 */
class ProfileViewModel(
    private val manageProfileUseCase: ManageProfileUseCase,
    private val getConfigUseCase: GetConfigUseCase,
    private val credentialStore: CredentialStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileListState())

    /** Observable profile list state. */
    val state: StateFlow<ProfileListState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reload the profile list and active profile from the configuration store.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val appConfig = getConfigUseCase().fold(
                ifLeft = { null },
                ifRight = { it },
            )
            val activeProfile = appConfig?.activeProfile
            val globalDisabledHash = appConfig?.global?.disabledHashAlgorithms ?: emptySet()
            val globalDisabledEnc = appConfig?.global?.disabledEncryptionAlgorithms ?: emptySet()

            manageProfileUseCase.list().fold(
                ifLeft = { error ->
                    _state.update { it.copy(loading = false, error = error.message) }
                },
                ifRight = { profiles ->
                    _state.update {
                        it.copy(
                            profiles = profiles.values.toList(),
                            activeProfile = activeProfile,
                            loading = false,
                            error = null,
                            globalDisabledHashAlgorithms = globalDisabledHash,
                            globalDisabledEncryptionAlgorithms = globalDisabledEnc,
                        )
                    }
                },
            )
        }
    }

    /**
     * Activate the profile with the given [name], making it the default for operations.
     * If [name] matches the already-active profile, it is deselected instead.
     *
     * @param name Profile name to select or deselect.
     */
    fun toggleActive(name: String) {
        viewModelScope.launch {
            val target = if (_state.value.activeProfile == name) null else name
            manageProfileUseCase.setActive(target).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = {
                    _state.update { it.copy(activeProfile = target) }
                },
            )
        }
    }

    /**
     * Deselect the currently active profile, clearing it to `null`.
     */
    fun deselectActive() {
        viewModelScope.launch {
            manageProfileUseCase.setActive(null).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = {
                    _state.update { it.copy(activeProfile = null) }
                },
            )
        }
    }

    /**
     * Delete the profile with the given [name] and refresh the list.
     *
     * @param name Profile name to remove.
     */
    fun delete(name: String) {
        viewModelScope.launch {
            manageProfileUseCase.remove(name).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = { refresh() },
            )
        }
    }

    /**
     * Enter inline profile creation mode, showing the new-profile input row.
     */
    fun startCreate() {
        _state.update { it.copy(creatingNew = true, error = null) }
    }

    /**
     * Cancel inline profile creation, hiding the new-profile input row.
     */
    fun cancelCreate() {
        _state.update { it.copy(creatingNew = false, error = null) }
    }

    /**
     * Confirm creation of a new profile with the given [name].
     *
     * The name must not be blank. On success the creation row is hidden and the
     * profile list is refreshed.
     *
     * @param name The name for the new profile.
     */
    fun confirmCreate(name: String) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "Profile name must not be blank.") }
            return
        }
        viewModelScope.launch {
            val profile = ProfileConfig(name = name.trim())
            manageProfileUseCase.upsert(profile).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = {
                    _state.update { it.copy(creatingNew = false) }
                    refresh()
                },
            )
        }
    }

    /**
     * Enter profile editing mode for the profile with the given [name].
     *
     * Loads the profile from the config store and switches the panel to
     * [ProfilePanelMode.Editing] with a pre-populated [ProfileEditState].
     *
     * @param name Profile name to edit.
     */
    fun startEdit(name: String) {
        viewModelScope.launch {
            manageProfileUseCase.get(name).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = { profile ->
                    val hasStored = hasStoredTsaPassword(profile)
                    _state.update {
                        it.copy(
                            mode = ProfilePanelMode.Editing(name),
                            editState = ProfileEditState.from(profile, hasStored),
                            error = null,
                        )
                    }
                },
            )
        }
    }

    /**
     * Cancel editing and return to the profile list view.
     */
    fun cancelEdit() {
        _state.update {
            it.copy(
                mode = ProfilePanelMode.Listing,
                editState = null,
                error = null,
            )
        }
    }

    /**
     * Apply a field-level transformation to the current [ProfileEditState].
     *
     * @param transform Function that receives the current edit state and returns the updated one.
     */
    fun updateEditState(transform: (ProfileEditState) -> ProfileEditState) {
        _state.update { current ->
            val editState = current.editState ?: return@update current
            current.copy(editState = transform(editState))
        }
    }

    /**
     * Persist the current edit state as a profile and return to the listing view.
     *
     * Converts [ProfileEditState] to a [ProfileConfig], calls [ManageProfileUseCase.upsert],
     * and on success stores the TSA password in the OS credential store (if provided)
     * then refreshes the profile list.
     */
    fun saveEdit() {
        val editState = _state.value.editState ?: return
        _state.update { it.copy(editState = editState.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val profile = editState.toProfileConfig()
            manageProfileUseCase.upsert(profile).fold(
                ifLeft = { error ->
                    _state.update {
                        it.copy(editState = editState.copy(saving = false, error = error.message))
                    }
                },
                ifRight = {
                    storeTsaPasswordIfNeeded(editState)
                    _state.update {
                        it.copy(
                            mode = ProfilePanelMode.Listing,
                            editState = null,
                        )
                    }
                    refresh()
                },
            )
        }
    }

    /**
     * Check whether a TSA password is already persisted for the given [profile].
     */
    private fun hasStoredTsaPassword(profile: ProfileConfig): Boolean {
        val key = profile.timestampServer?.credentialKey ?: return false
        return credentialStore?.getPassword(TSA_CREDENTIAL_SERVICE, key) != null
    }

    /**
     * Persist the TSA password from [editState] into the OS credential store
     * when a new password was entered and a username is present.
     */
    private fun storeTsaPasswordIfNeeded(editState: ProfileEditState) {
        if (editState.timestampPassword.isEmpty()) return
        val username = editState.timestampUsername.ifBlank { return }
        credentialStore?.setPassword(TSA_CREDENTIAL_SERVICE, username, editState.timestampPassword)
    }

    companion object {
        private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
    }
}
