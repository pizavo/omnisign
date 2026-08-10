package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.repository.ServerCreditsRepository
import cz.pizavo.omnisign.ui.model.ServerCreditsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel backing the Credits dialog's server section.
 *
 * [serverCreditsRepository] is `null` on the desktop target, which signs in-process and therefore
 * has no separate server to credit; the state stays [ServerCreditsState.NotApplicable] and the
 * section is never rendered. On the web target the bound repository is queried once on
 * construction.
 *
 * It owns only the *server* half of the dialog. The bundled list is a static resource packaged with
 * the build, read where it is rendered; this half is a network call against a deployment that may
 * be unreachable, may be someone else's, and may predate the endpoint entirely. Those failure modes
 * are what warrant a state machine, and all of them collapse to [ServerCreditsState.Unavailable] so
 * that a server problem can never stop the bundled components from being credited.
 *
 * @param serverCreditsRepository Source of the connected server's credits, or `null` when no server
 *   is involved (desktop).
 * @param ioDispatcher Dispatcher for the network fetch.
 */
class CreditsViewModel(
    private val serverCreditsRepository: ServerCreditsRepository?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _serverCredits = MutableStateFlow<ServerCreditsState>(
        if (serverCreditsRepository == null) ServerCreditsState.NotApplicable else ServerCreditsState.Loading,
    )

    /** Observable state of the server section. */
    val serverCredits: StateFlow<ServerCreditsState> = _serverCredits.asStateFlow()

    init {
        val repository = serverCreditsRepository
        if (repository != null) {
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    _serverCredits.value = runCatching { repository.get() }.fold(
                        onSuccess = { response ->
                            if (response.components.isEmpty()) {
                                ServerCreditsState.Unavailable
                            } else {
                                ServerCreditsState.Loaded(
                                    components = response.components,
                                    license = response.license,
                                    source = response.source,
                                )
                            }
                        },
                        onFailure = { ServerCreditsState.Unavailable },
                    )
                }
            }
        }
    }
}
