package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the global configuration settings dialog state.
 *
 * Loads the current [GlobalConfig] via [GetConfigUseCase], exposes it as a
 * [GlobalConfigEditState] for two-way binding, and persists changes via
 * [SetGlobalConfigUseCase]. Renewal jobs are loaded from [cz.pizavo.omnisign.domain.model.config.AppConfig.renewalJobs]
 * and saved back via [ConfigRepository] alongside the global config.
 *
 * @param getConfigUseCase Use-case for reading the current application configuration.
 * @param setGlobalConfigUseCase Use-case for updating and persisting the global configuration.
 * @param configRepository Repository for persisting renewal jobs at the [cz.pizavo.omnisign.domain.model.config.AppConfig] level.
 * @param credentialStore Optional OS credential store for persisting TSA passwords.
 */
class SettingsViewModel(
    private val getConfigUseCase: GetConfigUseCase,
    private val setGlobalConfigUseCase: SetGlobalConfigUseCase,
    private val configRepository: ConfigRepository? = null,
    private val credentialStore: CredentialStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(GlobalConfigEditState())

    private val _initialState = MutableStateFlow<GlobalConfigEditState?>(null)

    /** Observable global config edit state. */
    val state: StateFlow<GlobalConfigEditState> = _state.asStateFlow()

    /**
     * Whether the current edit state differs from the originally loaded state.
     *
     * Returns `false` until [load] completes successfully or when the current state
     * matches the initial snapshot (ignoring transient UI fields).
     */
    val hasChanges: StateFlow<Boolean> = combine(_state, _initialState) { current, initial ->
        initial != null && !current.contentEquals(initial)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Load the current global configuration from the config store and populate
     * the edit state. Call this when the settings dialog is opened.
     */
    fun load() {
        viewModelScope.launch {
            getConfigUseCase().fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.message) }
                },
                ifRight = { appConfig ->
                    val hasStored = hasStoredTsaPassword(appConfig.global)
                    val editState = GlobalConfigEditState.from(
                        config = appConfig.global,
                        hasStoredPassword = hasStored,
                        renewalJobs = appConfig.renewalJobs,
                        availableProfiles = appConfig.profiles.keys.sorted(),
                        activeProfile = appConfig.activeProfile,
                    )
                    _state.value = editState
                    _initialState.value = editState
                },
            )
        }
    }

    /**
     * Apply a field-level transformation to the current [GlobalConfigEditState].
     *
     * @param transform Function that receives the current edit state and returns the updated one.
     */
    fun updateState(transform: (GlobalConfigEditState) -> GlobalConfigEditState) {
        _state.update { transform(it) }
    }

    /**
     * Persist the current edit state as the global configuration.
     *
     * Converts [GlobalConfigEditState] to a [GlobalConfig], calls [SetGlobalConfigUseCase],
     * and on success stores the TSA password in the OS credential store (if provided).
     * The [onSuccess] callback is invoked after a successful save so the caller can
     * dismiss the dialog.
     *
     * @param onSuccess Callback invoked after the global config is successfully persisted.
     */
    fun save(onSuccess: () -> Unit = {}) {
        val current = _state.value
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            setGlobalConfigUseCase { current.toGlobalConfig() }.fold(
                ifLeft = { error ->
                    _state.update { it.copy(saving = false, error = error.message) }
                },
                ifRight = {
                    saveRenewalJobs(current)
                    storeTsaPasswordIfNeeded(current)
                    _state.update { it.copy(saving = false, error = null) }
                    onSuccess()
                },
            )
        }
    }

    /**
     * Check whether a TSA password is already persisted for the given global config.
     */
    private fun hasStoredTsaPassword(config: GlobalConfig): Boolean {
        val key = config.timestampServer?.credentialKey ?: return false
        return credentialStore?.getPassword(TSA_CREDENTIAL_SERVICE, key) != null
    }

    /**
     * Persist the TSA password from [editState] into the OS credential store
     * when a new password was entered and a username is present.
     */
    private fun storeTsaPasswordIfNeeded(editState: GlobalConfigEditState) {
        if (editState.timestampPassword.isEmpty()) return
        val username = editState.timestampUsername.ifBlank { return }
        credentialStore?.setPassword(TSA_CREDENTIAL_SERVICE, username, editState.timestampPassword)
    }

    /**
     * Persist the renewal jobs from [editState] into the application configuration
     * via [ConfigRepository]. Reads the current config, replaces the renewal jobs map,
     * and saves the result.
     */
    private suspend fun saveRenewalJobs(editState: GlobalConfigEditState) {
        val repo = configRepository ?: return
        val appConfig = repo.getCurrentConfig()
        val jobMap = editState.renewalJobs.associateBy { it.name }
        repo.saveConfig(appConfig.copy(renewalJobs = jobMap))
    }

    companion object {
        private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
    }
}

