package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.ui.model.ProfileListState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the profile list panel state.
 *
 * Loads profiles via [ManageProfileUseCase], tracks the currently active profile,
 * and exposes actions for selecting, deselecting, and deleting profiles.
 *
 * @param manageProfileUseCase Use case for CRUD operations on configuration profiles.
 * @param getConfigUseCase Use case for reading the current application configuration.
 */
class ProfileViewModel(
    private val manageProfileUseCase: ManageProfileUseCase,
    private val getConfigUseCase: GetConfigUseCase,
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

            val activeProfile = getConfigUseCase().fold(
                ifLeft = { null },
                ifRight = { it.activeProfile },
            )

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
}
