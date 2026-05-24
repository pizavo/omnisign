package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.ui.model.TrustedCertsPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the read-only trusted certificates overview panel.
 *
 * The app-managed [TrustStore] is the source of truth for directly-trusted certificates.
 * This ViewModel reads the active profile name from the configuration, then lists the
 * certificates referenced by the global scope and the active profile scope so the UI can
 * present them in separate, clearly labeled sections. The panel is view-only; adding and
 * removing certificates happens in the Settings dialog (global scope) and the profile editor
 * (profile scope), staged with the rest of those forms.
 *
 * On targets without a [TrustStore] binding (web), [trustStore] is `null`; the panel state
 * is marked unavailable and rendered empty rather than crashing.
 *
 * @param getConfigUseCase Use-case for reading the current application configuration.
 * @param trustStore App-managed trust store, or `null` when no backend is wired in (web).
 */
class TrustedCertsViewModel(
    private val getConfigUseCase: GetConfigUseCase,
    private val trustStore: TrustStore? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(TrustedCertsPanelState(available = trustStore != null))

    /** Observable panel state. */
    val state: StateFlow<TrustedCertsPanelState> = _state.asStateFlow()

    /**
     * Reload trusted certificates from the trust store.
     *
     * Resolves the active profile name from the configuration, then lists the global scope
     * and (when a profile is active) the active profile scope. A no-op load that yields an empty,
     * unavailable state when no [TrustStore] backend is present.
     *
     * Call this when the panel becomes visible or after the user saves certificate changes in the
     * Settings dialog or the profile editor.
     */
    fun refresh() {
        val store = trustStore ?: run {
            _state.update { TrustedCertsPanelState(available = false) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val activeProfileName = getConfigUseCase().fold(
                ifLeft = { null },
                ifRight = { it.activeProfile },
            )

            val globalResult = store.list(TrustScope.Global)
            val profileResult = activeProfileName?.let { store.list(TrustScope.Profile(it)) }

            val error = globalResult.fold(ifLeft = { it.message }, ifRight = { null })
                ?: profileResult?.fold(ifLeft = { it.message }, ifRight = { null })

            _state.update {
                TrustedCertsPanelState(
                    profileName = activeProfileName,
                    profileCertificates = profileResult?.fold(ifLeft = { emptyList() }, ifRight = { it }).orEmpty(),
                    globalCertificates = globalResult.fold(ifLeft = { emptyList() }, ifRight = { it }),
                    available = true,
                    loading = false,
                    error = error,
                )
            }
        }
    }
}
