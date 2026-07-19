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
 * [capabilitiesRepository] is `null` on the desktop target (a local app with no server), in which case
 * [capabilities] is set to an explicit all-permitted, unbranded value — desktop can do everything. On
 * the web target the bound [CapabilitiesRepository] is queried once on construction: the server's
 * `allowedOperations` narrow the flags and its `organizationName` fills in the operator label. The
 * flags **default to denied** ([ServerCapabilities]), so the web target fails closed — a fetch that
 * fails (server unreachable, transient error) leaves every operation denied rather than exposing a
 * button the server would reject.
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
        if (repository == null) {
            _capabilities.value = ServerCapabilities(canValidate = true, canSign = true, canTimestamp = true)
        } else {
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
