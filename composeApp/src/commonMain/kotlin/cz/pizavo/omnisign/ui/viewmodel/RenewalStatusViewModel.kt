package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports whether scheduled renewal needs the user's attention, so the toolbar can mark the settings
 * entry without the user having to open it.
 *
 * The same status is already shown in Settings > Scheduler, but only to someone who goes looking. A
 * scheduler that has been failing for weeks is exactly the case nobody thinks to check, so the one
 * bit that says "go and look" is surfaced where it is visible by default.
 *
 * Reading the record here rather than through [SettingsViewModel] keeps startup cheap: that view
 * model's `load()` also queries the OS scheduler, which spawns a process.
 *
 * @param runRecordStore Supplies the persisted status of the last run. `null` on targets without a
 *   backend (web), where renewal never runs and the status is always clear.
 * @param activityProbe Tells a run marker belonging to a live run apart from one a killed run left
 *   behind. `null` on targets that never run batch renewal.
 * @param ioDispatcher Dispatcher for the blocking store and probe reads.
 */
class RenewalStatusViewModel(
    private val runRecordStore: RenewalRunRecordStore? = null,
    private val activityProbe: RenewalActivityProbe? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _needsAttention = MutableStateFlow(false)

    /** Whether the last renewal run failed, or began and never finished. */
    val needsAttention: StateFlow<Boolean> = _needsAttention.asStateFlow()

    /**
     * Re-read the renewal status.
     *
     * Renewal runs in a separate process started by the OS scheduler, so the record can change while
     * the app is open. Callers refresh at startup and whenever the settings dialog closes, which is
     * when a user could have acted on the badge; it is not polled.
     */
    fun refresh() {
        viewModelScope.launch {
            _needsAttention.value = withContext(ioDispatcher) { evaluate() }
        }
    }

    /**
     * Whether the persisted status warrants the badge: any run has failed since the last success, or
     * a run marker survives with no run currently executing, which means that run was killed.
     */
    private fun evaluate(): Boolean {
        val record = try {
            runRecordStore?.load()
        } catch (_: Exception) {
            null
        } ?: return false
        if (record.failuresSinceSuccess > 0) return true
        if (record.runStartedAt == null) return false
        return activityProbe?.isRunInFlight() != true
    }
}
