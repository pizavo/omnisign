package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.ui.model.TrustedCertsPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the trusted certificates overview panel.
 *
 * Loads the current application configuration and splits trusted certificates into
 * two groups — those scoped to the active profile and those in the global config —
 * so the UI can present them in separate, clearly labeled sections.
 *
 * @param getConfigUseCase Use-case for reading the current application configuration.
 */
class TrustedCertsViewModel(
    private val getConfigUseCase: GetConfigUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(TrustedCertsPanelState())

    /** Observable panel state. */
    val state: StateFlow<TrustedCertsPanelState> = _state.asStateFlow()

    /**
     * Reload trusted certificates from the configuration store.
     *
     * Call this when the panel becomes visible or after the user modifies
     * certificates in the settings dialog or profile editor.
     */
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            getConfigUseCase().fold(
                ifLeft = { error ->
                    _state.update { it.copy(loading = false, error = error.message) }
                },
                ifRight = { appConfig ->
                    val activeProfileName = appConfig.activeProfile
                    val profileCerts = activeProfileName
                        ?.let { appConfig.profiles[it] }
                        ?.validation
                        ?.trustedCertificates
                        .orEmpty()
                    val globalCerts = appConfig.global.validation.trustedCertificates

                    _state.update {
                        TrustedCertsPanelState(
                            profileName = activeProfileName,
                            profileCertificates = profileCerts,
                            globalCertificates = globalCerts,
                            loading = false,
                            error = null,
                        )
                    }
                },
            )
        }
    }
}

