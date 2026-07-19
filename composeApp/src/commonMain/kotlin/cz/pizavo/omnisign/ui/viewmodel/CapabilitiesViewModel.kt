package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.ui.model.ServerCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel exposing which operations the connected server permits (so the UI can hide affordances
 * for operations that are disabled server-side) and the server operator's deploy-time branding label.
 *
 * [capabilitiesRepository] is `null` on the desktop target (a local app with no server), in
 * which case [capabilities] stays at its all-permitted, unbranded default. On the web target the bound
 * [CapabilitiesRepository] is queried once on construction: the server's `allowedOperations` narrow the
 * flags and its `organizationName` fills in the operator label. A fetch failure leaves the optimistic
 * default in place — the operation would surface its own error if later attempted.
 *
 * @param capabilitiesRepository Source of the server's published capabilities, or `null` when
 *   no server is involved (desktop).
 * @param ioDispatcher Dispatcher for the network fetch.
 */
class CapabilitiesViewModel(
    private val capabilitiesRepository: CapabilitiesRepository?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _capabilities = MutableStateFlow(ServerCapabilities())

    /** Observable server capabilities; all-permitted until (and unless) a server narrows them. */
    val capabilities: StateFlow<ServerCapabilities> = _capabilities.asStateFlow()

    init {
        val repository = capabilitiesRepository
        if (repository != null) {
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    runCatching { repository.get() }.onSuccess { response ->
                        val allowed = response.allowedOperations.mapTo(mutableSetOf()) { it.uppercase() }
                        _capabilities.value = ServerCapabilities(
                            canValidate = OPERATION_VALIDATE in allowed,
                            canSign = OPERATION_SIGN in allowed,
                            canTimestamp = OPERATION_TIMESTAMP in allowed,
                            authEnabled = response.authEnabled,
                            organizationName = response.organizationName?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val OPERATION_VALIDATE = "VALIDATE"
        private const val OPERATION_SIGN = "SIGN"
        private const val OPERATION_TIMESTAMP = "TIMESTAMP"
    }
}
