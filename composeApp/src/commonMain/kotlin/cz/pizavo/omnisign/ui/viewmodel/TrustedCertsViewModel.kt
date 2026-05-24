package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
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
 * ViewModel for the trusted certificates management panel.
 *
 * The app-managed [TrustStore] is the source of truth for directly-trusted certificates.
 * This ViewModel reads the active profile name from the configuration, then lists the
 * certificates referenced by the global scope and the active profile scope so the UI can
 * present them in separate, clearly labeled sections. It also drives adding (from picked
 * certificate file bytes) and removing (by fingerprint) within a chosen [TrustScope].
 *
 * On targets without a [TrustStore] binding (web), [trustStore] is `null`; the panel state
 * is marked unavailable and rendered read-only and empty rather than crashing.
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
     * and (when a profile is active) the profile scope. A no-op load that yields an empty,
     * unavailable state when no [TrustStore] backend is present.
     *
     * Call this when the panel becomes visible or after the user adds or removes a certificate.
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

    /**
     * Import a certificate from picked file [certBytes] into [scope] with the given [type].
     *
     * On success the panel is refreshed; on failure the human-readable error is surfaced via
     * [TrustedCertsPanelState.addError]. A no-op when no [TrustStore] backend is present.
     *
     * @param scope Target trust scope (global or a profile).
     * @param certBytes Raw certificate file content (PEM or DER).
     * @param type Trust role granted in this scope.
     * @param source Provenance recorded in the trust index — the path the certificate was read from.
     */
    fun addCertificate(scope: TrustScope, certBytes: ByteArray, type: TrustedCertificateType, source: String) {
        val store = trustStore ?: return
        _state.update { it.copy(addError = null) }
        viewModelScope.launch {
            store.add(scope, certBytes, type, source = source).fold(
                ifLeft = { error -> _state.update { it.copy(addError = error.message) } },
                ifRight = { refresh() },
            )
        }
    }

    /**
     * Remove the certificate with [fingerprint] from [scope].
     *
     * On success the panel is refreshed; on failure the human-readable error is surfaced via
     * [TrustedCertsPanelState.error]. A no-op when no [TrustStore] backend is present.
     *
     * @param scope Scope to remove the reference from.
     * @param fingerprint Algorithm-prefixed SHA-256 fingerprint of the certificate to remove.
     */
    fun removeCertificate(scope: TrustScope, fingerprint: String) {
        val store = trustStore ?: return
        viewModelScope.launch {
            store.remove(scope, fingerprint).fold(
                ifLeft = { error -> _state.update { it.copy(error = error.message) } },
                ifRight = { refresh() },
            )
        }
    }

    /**
     * Surface a human-readable certificate add error originating from the UI, such as a failure
     * to read the picked certificate file before it reaches the store.
     *
     * @param message Human-readable error to display.
     */
    fun reportAddError(message: String) {
        _state.update { it.copy(addError = message) }
    }

    /**
     * Clear the last certificate add error, typically when the user starts a new interaction.
     */
    fun clearAddError() {
        _state.update { it.copy(addError = null) }
    }
}
