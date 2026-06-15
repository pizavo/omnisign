package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.model.PendingTrustedCert
import cz.pizavo.omnisign.ui.platform.loadUseNativeTitleBar
import cz.pizavo.omnisign.ui.platform.saveUseNativeTitleBar
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * ViewModel managing the global configuration settings dialog state.
 *
 * Loads the current [GlobalConfig] via [GetConfigUseCase], exposes it as a
 * [GlobalConfigEditState] for two-way binding, and persists changes via
 * [SetGlobalConfigUseCase]. Renewal jobs are loaded from [cz.pizavo.omnisign.domain.model.config.AppConfig.renewalJobs]
 * and saved back via [ConfigRepository] alongside the global config.
 *
 * When a [SchedulerPort] is available, the OS-level renewal scheduler is
 * automatically installed or removed on save depending on whether renewal jobs
 * are configured and an executable path is available (auto-detected or manual).
 *
 * @param getConfigUseCase Use-case for reading the current application configuration.
 * @param setGlobalConfigUseCase Use-case for updating and persisting the global configuration.
 * @param configRepository Repository for persisting renewal jobs at the [cz.pizavo.omnisign.domain.model.config.AppConfig] level.
 * @param credentialStore Optional OS credential store for persisting TSA passwords.
 * @param schedulerPort Optional scheduler port for managing the OS-level daily renewal job.
 * @param autoDetectedExecutablePath Auto-detected absolute path of the running executable.
 *   When available, the executable path field is hidden from the user and this value is used
 *   automatically. `null` when auto-detection is unavailable (e.g. `java -jar` or Wasm),
 *   in which case a manual text field is shown as a fallback.
 * @param isLinuxDesktop Whether the application is running on JVM Linux desktop. When `true`,
 *   the Appearance > Window section is shown in the settings dialog and the native title bar
 *   preference is loaded/saved via [loadUseNativeTitleBar]/[saveUseNativeTitleBar].
 * @param ioDispatcher Dispatcher for blocking scheduler process calls.
 * @param trustedListRefreshPort Optional trusted-list refresh backend. Drives the
 *   "Refresh now" control and the last-refreshed indicator. `null` on targets
 *   without a DSS backend (web).
 * @param configArchive Optional full-configuration archive port backing the Backup
 *   (export / import) settings section. `null` on targets without a JVM file backend (web).
 * @param trustStore Optional app-managed trust store backing the Trusted Certificates section.
 *   `null` on targets without a backend (web), where that section renders unavailable.
 */
class SettingsViewModel(
    private val getConfigUseCase: GetConfigUseCase,
    private val setGlobalConfigUseCase: SetGlobalConfigUseCase,
    private val configRepository: ConfigRepository? = null,
    private val credentialStore: CredentialStore? = null,
    private val schedulerPort: SchedulerPort? = null,
    private val autoDetectedExecutablePath: String? = null,
    private val isLinuxDesktop: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val trustedListRefreshPort: TrustedListRefreshPort? = null,
    private val configArchive: ConfigArchivePort? = null,
    private val trustStore: TrustStore? = null,
    private val renewalRunRecordStore: RenewalRunRecordStore? = null,
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
     * `true` while any trusted-list refresh is in flight. Drives the Settings
     * refresh control: clickable button when `false`, spinner when `true`.
     */
    val trustedListRefreshing: StateFlow<Boolean> =
        trustedListRefreshPort?.running
            ?.map { it.isNotEmpty() }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    /** When the trusted lists were last refreshed, or `null` if never this process. */
    val trustedListLastRefreshAt: StateFlow<Instant?> =
        trustedListRefreshPort?.lastRefreshAt ?: MutableStateFlow<Instant?>(null)

    /**
     * Live progress of loading the trusted lists (EU LOTL members + custom lists), driving the
     * determinate refresh bar in Settings. Idle when no refresh backend is available (web).
     */
    val trustedListLoadProgress: StateFlow<TrustedListLoadProgress> =
        trustedListRefreshPort?.trustedListLoadProgress ?: MutableStateFlow(TrustedListLoadProgress())

    /**
     * Trigger an immediate trusted-list refresh. No-op when no refresh backend is
     * available (web) or a refresh is already running.
     */
    fun refreshTrustedListsNow() {
        val port = trustedListRefreshPort ?: return
        if (trustedListRefreshing.value) return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { port.refreshNow() }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Trusted-list refresh failed: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

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
                    val installed = withContext(ioDispatcher) {
                        try { schedulerPort?.isInstalled() == true } catch (_: Exception) { false }
                    }
                    val runRecord = withContext(ioDispatcher) {
                        try { renewalRunRecordStore?.load() } catch (_: Exception) { null }
                    }
                    val trustedCerts = trustStore?.list(TrustScope.Global)
                        ?.fold(ifLeft = { emptyList() }, ifRight = { it }).orEmpty()
                    val editState = GlobalConfigEditState.from(
                        config = appConfig.global,
                        hasStoredPassword = hasStored,
                        renewalJobs = appConfig.renewalJobs,
                        availableProfiles = appConfig.profiles.keys.sorted(),
                        activeProfile = appConfig.activeProfile,
                        schedulerConfig = appConfig.schedulerConfig,
                        schedulerInstalled = installed,
                        schedulerAutoDetectedPath = autoDetectedExecutablePath,
                        trustedCertificates = trustedCerts,
                    ).copy(
                        trustedCertsAvailable = trustStore != null,
                        renewalRunRecord = runRecord,
                    ).let {
                        if (isLinuxDesktop) it.copy(
                            useNativeTitleBar = loadUseNativeTitleBar() ?: false,
                            showNativeTitleBarOption = true,
                        ) else it
                    }
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
     * Parse and stage a global-scope trusted-certificate addition (committed on [save]).
     *
     * The certificate is parsed via [cz.pizavo.omnisign.domain.repository.TrustStore.inspect] so the
     * staged row can show its subject and expiry. If its fingerprint is already trusted in the global
     * scope (in the baseline minus pending removals, or already staged), nothing is staged and an
     * "already trusted" message is surfaced via [GlobalConfigEditState.trustedCertAddError]. A parse
     * failure surfaces the same way. A no-op when no trust store backend is present.
     *
     * @param bytes Raw certificate file content (PEM or DER).
     * @param type Trust role to grant when applied.
     * @param source Path the certificate was read from, recorded as provenance on save.
     */
    fun stageGlobalTrustedCert(bytes: ByteArray, type: TrustedCertificateType, source: String) {
        val store = trustStore ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { store.inspect(bytes) }.fold(
                ifLeft = { error -> _state.update { it.copy(trustedCertAddError = error.message) } },
                ifRight = { parsed ->
                    _state.update { current ->
                        val trustedFingerprints = current.trustedCertificates
                            .filter { it.fingerprint !in current.pendingTrustedCertRemovals }
                            .map { it.fingerprint } +
                            current.pendingTrustedCertAdds.map { it.fingerprint }
                        if (parsed.fingerprint in trustedFingerprints) {
                            current.copy(trustedCertAddError = "This certificate is already trusted in the global scope.")
                        } else {
                            current.copy(
                                pendingTrustedCertAdds = current.pendingTrustedCertAdds + PendingTrustedCert(
                                    source = source,
                                    type = type,
                                    bytes = bytes,
                                    fingerprint = parsed.fingerprint,
                                    subjectDN = parsed.subjectDN,
                                    notAfter = parsed.notAfter,
                                ),
                                trustedCertAddError = null,
                            )
                        }
                    }
                },
            )
        }
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
        if (current.hasSchedulerTimeError) {
            _state.update { it.copy(error = "Scheduler time is invalid — hour must be 0\u201323, minute must be 0\u201359.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            setGlobalConfigUseCase { current.toGlobalConfig() }.fold(
                ifLeft = { error ->
                    _state.update { it.copy(saving = false, error = error.message) }
                },
                ifRight = {
                    saveAppLevelConfig(current)
                    val (schedulerError, installed) = withContext(ioDispatcher) {
                        val err = syncScheduler(current)
                        val inst = try { schedulerPort?.isInstalled() == true } catch (_: Exception) { false }
                        err to inst
                    }
                    storeTsaPasswordIfNeeded(current)
                    if (isLinuxDesktop) saveUseNativeTitleBar(current.useNativeTitleBar)

                    val store = trustStore
                    val certError = if (store != null) {
                        withContext(ioDispatcher) {
                            applyStagedTrustedCertChanges(
                                store = store,
                                scope = TrustScope.Global,
                                removals = current.pendingTrustedCertRemovals,
                                additions = current.pendingTrustedCertAdds,
                            )
                        }
                    } else {
                        null
                    }

                    if (certError != null) {
                        _state.update { it.copy(saving = false, error = certError, schedulerInstalled = installed) }
                    } else {
                        val newBaseline = store
                            ?.let { withContext(ioDispatcher) { it.list(TrustScope.Global) } }
                            ?.fold(ifLeft = { emptyList() }, ifRight = { it }).orEmpty()
                        _state.update {
                            it.copy(
                                saving = false,
                                error = schedulerError,
                                schedulerInstalled = installed,
                                trustedCertificates = newBaseline,
                                pendingTrustedCertAdds = emptyList(),
                                pendingTrustedCertRemovals = emptySet(),
                                trustedCertAddError = null,
                            )
                        }
                        _initialState.value = _state.value
                        if (schedulerError == null) onSuccess()
                    }
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
     * Persist renewal jobs and scheduler config from [editState] into the
     * application configuration in a single atomic write via [ConfigRepository].
     */
    private suspend fun saveAppLevelConfig(editState: GlobalConfigEditState) {
        val repo = configRepository ?: return
        val appConfig = repo.getCurrentConfig()
        val jobMap = editState.renewalJobs.associateBy { it.name }
        val schedulerCfg = SchedulerConfig(
            cliExecutablePath = editState.effectiveSchedulerExecutablePath,
            runAtHour = editState.schedulerHour.toIntOrNull()?.coerceIn(0, 23) ?: 2,
            runAtMinute = editState.schedulerMinute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
            logFilePath = editState.schedulerLogFile.trim().ifBlank { null },
        )
        repo.saveConfig(
            appConfig.copy(
                renewalJobs = jobMap,
                schedulerConfig = schedulerCfg,
            )
        )
    }

    /**
     * Synchronize the OS scheduler with the current edit state.
     *
     * When renewal jobs exist and an executable path is available (auto-detected or
     * manually entered), the scheduler is installed (or updated). Otherwise, the
     * scheduler is uninstalled.
     * If the [schedulerPort] is not available the method is a no-op.
     *
     * @return A human-readable error message when the scheduler operation failed,
     *   or `null` on success.
     */
    private fun syncScheduler(editState: GlobalConfigEditState): String? {
        val port = schedulerPort ?: return null
        val exePath = editState.effectiveSchedulerExecutablePath
        if (editState.renewalJobs.isNotEmpty() && exePath != null) {
            return try {
                port.install(
                    cliExecutablePath = exePath,
                    runAtHour = editState.schedulerHour.toIntOrNull()?.coerceIn(0, 23) ?: 2,
                    runAtMinute = editState.schedulerMinute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                    logFilePath = editState.schedulerLogFile.trim().ifBlank { null },
                )
                null
            } catch (e: Exception) {
                "Failed to install OS scheduler: ${e.message ?: "unknown error"}"
            }
        } else {
            try {
                port.uninstall()
            } catch (_: Exception) { }
            return null
        }
    }

    /** Whether configuration export/import is available (JVM desktop only). */
    val canBackup: Boolean get() = configArchive != null

    /**
     * Build the full-configuration export archive — a ZIP of the configuration plus the trusted
     * certificates referenced by the global and profile scopes.
     *
     * @return The archive bytes, or `null` when no archive backend is available or the export
     *   failed (the failure reason is surfaced via [state]'s `error`).
     */
    suspend fun buildConfigArchive(): ByteArray? {
        val archive = configArchive ?: return null
        return withContext(ioDispatcher) { archive.exportFullConfig() }.fold(
            ifLeft = { error -> _state.update { it.copy(error = error.message) }; null },
            ifRight = { it },
        )
    }

    /**
     * Import a full-configuration archive, replacing the entire current configuration, then reload
     * the edit state so the dialog reflects the imported values. Failures are surfaced via
     * [state]'s `error`.
     *
     * @param bytes The ZIP archive bytes chosen by the user.
     */
    fun importConfiguration(bytes: ByteArray) {
        val archive = configArchive ?: return
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            withContext(ioDispatcher) { archive.importFullConfig(bytes) }.fold(
                ifLeft = { error -> _state.update { it.copy(error = error.message) } },
                ifRight = { load() },
            )
        }
    }

    companion object {
        private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
    }
}

