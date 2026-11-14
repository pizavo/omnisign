package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.flatMap
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.SchedulerConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.localizableText
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
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
import cz.pizavo.omnisign.ui.model.SettingsError
import cz.pizavo.omnisign.ui.model.TrustedCertAddError
import cz.pizavo.omnisign.ui.platform.loadUseNativeTitleBar
import cz.pizavo.omnisign.ui.platform.saveUseNativeTitleBar
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
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
 * @param ioDispatcher Dispatcher for blocking calls: scheduler processes, and the credential store,
 *   which can block until the user answers the keyring's unlock prompt.
 * @param trustedListRefreshPort Optional trusted-list refresh backend. Drives the
 *   "Refresh now" control and the last-refreshed indicator. `null` on targets
 *   without a DSS backend (web).
 * @param configArchive Optional full-configuration archive port backing the Backup
 *   (export / import) settings section: on the desktop it exports and imports local archives, on
 *   the web it downloads the server's configuration and refuses an import. `null` where neither is
 *   bound, which hides the section.
 * @param trustStore Optional trust store backing the Trusted Certificates section: the desktop's
 *   own, or on the web the server's, read through a read-only remote store. `null` where neither is
 *   bound, and the section then renders unavailable.
 * @param renewalRunRecordStore Optional store of the record the last batch-renewal run left, read on
 *   load to show that run's outcome. `null` on targets that never run batch renewal (web).
 * @param renewalActivityProbe Optional probe telling whether a renewal run is executing right now, so
 *   that a run marker left in the record can be reported as interrupted rather than as still in
 *   progress. `null` on targets that never run batch renewal (web).
 * @param today The current date, which a save that changes an algorithm expiration level stamps as
 *   the policy update date.
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
    private val renewalActivityProbe: RenewalActivityProbe? = null,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {

    private val _state = MutableStateFlow(GlobalConfigEditState())

    private val _initialState = MutableStateFlow<GlobalConfigEditState?>(null)

    /** Observable global config edit state. */
    val state: StateFlow<GlobalConfigEditState> = _state.asStateFlow()

    /**
     * Whether Save has something to do: the current edit state differs from the state last loaded
     * or saved, ignoring transient UI fields, or the OS scheduler job is missing where Save
     * registers it, as [GlobalConfigEditState.schedulerInstallPending] tells.
     *
     * Returns `false` until [load] completes successfully.
     */
    val hasChanges: StateFlow<Boolean> = combine(_state, _initialState) { current, initial ->
        initial != null && (current.schedulerInstallPending || !current.contentEquals(initial))
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
                _state.update { it.copy(error = SettingsError.RefreshFailed(e.message ?: e::class.simpleName.orEmpty())) }
            }
        }
    }

    /**
     * Load the current global configuration from the config store and populate
     * the edit state. Call this when the settings dialog is opened.
     *
     * When renewal jobs and an executable path for the OS scheduler are configured but the scheduler
     * job is not registered, as after a save that failed to register it in a dialog since closed,
     * the job is marked as [GlobalConfigEditState.schedulerInstallPending], so Save is enabled to
     * register it. It is not marked where the OS scheduler cannot register the job at all, as on
     * Linux without a systemd user instance, since no save could register it there.
     */
    fun load() {
        viewModelScope.launch {
            getConfigUseCase().fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = SettingsError.Domain(error.localizableText())) }
                },
                ifRight = { appConfig ->
                    val hasStored = withContext(ioDispatcher) { hasStoredTsaPassword(appConfig.global) }
                    val installed = withContext(ioDispatcher) {
                        try { schedulerPort?.isInstalled() == true } catch (_: Exception) { false }
                    }
                    val runRecord = withContext(ioDispatcher) {
                        try { renewalRunRecordStore?.load() } catch (_: Exception) { null }
                    }
                    val runInFlight = withContext(ioDispatcher) {
                        runRecord?.runStartedAt != null && renewalActivityProbe?.isRunInFlight() == true
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
                    ).let {
                        it.copy(
                            trustedCertsAvailable = trustStore != null,
                            renewalRunRecord = runRecord,
                            renewalRunInFlight = runInFlight,
                            schedulerInstallPending = !installed && it.renewalJobs.isNotEmpty() &&
                                    it.effectiveSchedulerExecutablePath != null && schedulerAvailable(),
                        )
                    }.let {
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
                ifLeft = { error -> _state.update { it.copy(trustedCertAddError = TrustedCertAddError.Domain(error.localizableText())) } },
                ifRight = { parsed ->
                    _state.update { current ->
                        val trustedFingerprints = current.trustedCertificates
                            .filter { it.fingerprint !in current.pendingTrustedCertRemovals }
                            .map { it.fingerprint } +
                            current.pendingTrustedCertAdds.map { it.fingerprint }
                        if (parsed.fingerprint in trustedFingerprints) {
                            current.copy(trustedCertAddError = TrustedCertAddError.AlreadyTrusted)
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
     * Nothing happens while a save is already running. A field whose value is out of its range, as
     * [GlobalConfigEditState.invalidFieldError] names it, becomes the error and nothing is saved, and
     * so does a TSA password to store when the [credentialStore] keeps passwords in memory only, as
     * the configuration would then name a password that is gone once the application closes.
     *
     * Otherwise a newly entered TSA password is stored in the OS credential store first, which can
     * wait for the keyring to be unlocked. The configuration is then read again, so that what the CLI
     * or another instance of the application saved while the dialog was open, that wait included, is
     * kept: the [GlobalConfigEditState] is applied to the global configuration as it is saved through
     * [SetGlobalConfigUseCase], keeping what the dialog does not show, and the renewal jobs and
     * scheduler settings are saved after it. A password the store does not keep becomes the error and
     * nothing is saved, so the configuration never names a password that is not there; a failed read,
     * or a failed write of either part, becomes the error too, and the scheduler is then left as it
     * was. A password stored before such a failure stays in the form, and the next save stores it
     * again.
     *
     * @param onSuccess Callback invoked once the configuration, the OS scheduler job and the staged
     *   certificate changes are all saved, so the caller can dismiss the dialog. It is not invoked
     *   when any of them failed, nor when the form was edited while the save ran.
     */
    fun save(onSuccess: () -> Unit = {}) {
        val current = _state.value
        if (current.saving) return
        val refusal = current.invalidFieldError
            ?: SettingsError.TsaPasswordNotKept.takeIf {
                current.storesTimestampPassword && credentialStore?.isPersistent == false
            }
        refusal?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            withContext(ioDispatcher) { storeTsaPasswordIfNeeded(current) }
                .flatMap { getConfigUseCase() }
                .flatMap { setGlobalConfigUseCase { current.appliedTo(this, today()) } }
                .flatMap { saveAppLevelConfig(current) }
                .fold(
                    ifLeft = { error ->
                        _state.update { it.copy(saving = false, error = SettingsError.Domain(error.localizableText())) }
                    },
                    ifRight = { finishSave(current, onSuccess) },
                )
        }
    }

    /**
     * Finish a save of [saved], the edit state as it was when Save was clicked, once the
     * configuration is written: sync the OS scheduler, apply the staged certificate changes, and
     * make [saved] the new baseline, with a TSA password the credential store now keeps cleared from
     * it and from the form, unless the form's password was changed meanwhile. Edits made while the
     * save ran stay in the form, unsaved, and keep the dialog open, so they are neither lost nor
     * taken for saved. A scheduler job that could not be registered is marked as
     * [GlobalConfigEditState.schedulerInstallPending] in the form, also when the certificate changes
     * fail too, which keeps Save enabled to try again, unless the OS scheduler cannot register the
     * job at all, as on Linux without a systemd user instance.
     */
    private suspend fun finishSave(saved: GlobalConfigEditState, onSuccess: () -> Unit) {
        val (schedulerError, installed) = withContext(ioDispatcher) {
            val err = syncScheduler(saved)
            val inst = try { schedulerPort?.isInstalled() == true } catch (_: Exception) { false }
            err to inst
        }
        if (isLinuxDesktop) saveUseNativeTitleBar(saved.useNativeTitleBar)

        val store = trustStore
        val certError = store?.let {
            withContext(ioDispatcher) {
                applyStagedTrustedCertChanges(
                    store = it,
                    scope = TrustScope.Global,
                    removals = saved.pendingTrustedCertRemovals,
                    additions = saved.pendingTrustedCertAdds,
                )
            }
        }
        val schedulerInstallPending = schedulerError != null && schedulerAvailable()
        if (certError != null) {
            _state.update {
                it.copy(
                    saving = false,
                    error = SettingsError.Domain(certError.localizableText()),
                    schedulerInstalled = installed,
                    schedulerInstallPending = schedulerInstallPending,
                )
            }
            return
        }

        val newBaseline = store
            ?.let { withContext(ioDispatcher) { it.list(TrustScope.Global) } }
            ?.fold(ifLeft = { emptyList() }, ifRight = { it }).orEmpty()
        val passwordKept = credentialStore != null && saved.storesTimestampPassword
        val baseline = saved.copy(
            saving = false,
            error = schedulerError,
            schedulerInstalled = installed,
            trustedCertificates = newBaseline,
            pendingTrustedCertAdds = emptyList(),
            pendingTrustedCertRemovals = emptySet(),
            trustedCertAddError = null,
        ).let { if (passwordKept) it.copy(timestampPassword = Sensitive(""), hasStoredPassword = true) else it }
        _state.update { live ->
            live.copy(
                saving = false,
                error = schedulerError,
                schedulerInstalled = installed,
                schedulerInstallPending = schedulerInstallPending,
                trustedCertificates = newBaseline,
                pendingTrustedCertAdds = live.pendingTrustedCertAdds - saved.pendingTrustedCertAdds.toSet(),
                pendingTrustedCertRemovals = live.pendingTrustedCertRemovals - saved.pendingTrustedCertRemovals,
                trustedCertAddError = null,
            ).let {
                if (passwordKept && it.timestampUsername == saved.timestampUsername) {
                    it.copy(
                        timestampPassword = it.timestampPassword.takeUnless { password ->
                            password == saved.timestampPassword
                        } ?: Sensitive(""),
                        hasStoredPassword = true,
                    )
                } else {
                    it
                }
            }
        }
        _initialState.value = baseline
        if (schedulerError == null && _state.value.contentEquals(baseline)) onSuccess()
    }

    /**
     * Check whether a TSA password is already persisted for the given global config.
     *
     * Blocks while the keyring asks to be unlocked, so callers run it on [ioDispatcher].
     */
    private fun hasStoredTsaPassword(config: GlobalConfig): Boolean {
        val key = config.timestampServer?.credentialKey ?: return false
        return credentialStore?.getPassword(TSA_CREDENTIAL_SERVICE, key) != null
    }

    /**
     * Persist the TSA password from [editState] into the OS credential store under its username,
     * when the save stores one, as [GlobalConfigEditState.storesTimestampPassword] tells.
     *
     * Blocks while the keyring asks to be unlocked, so callers run it on [ioDispatcher].
     *
     * @return `Unit` when there was nothing to store or the store kept the password, otherwise the
     *   store's error.
     */
    private fun storeTsaPasswordIfNeeded(editState: GlobalConfigEditState): OperationResult<Unit> {
        if (!editState.storesTimestampPassword) return Unit.right()
        return credentialStore?.setPassword(
            TSA_CREDENTIAL_SERVICE,
            editState.timestampUsername,
            editState.timestampPassword.value,
        ) ?: Unit.right()
    }

    /**
     * Persist renewal jobs and scheduler config from [editState] into the
     * application configuration in a single atomic write via [ConfigRepository].
     *
     * @return `Unit` when the write succeeded or there is no [configRepository], otherwise its error.
     */
    private suspend fun saveAppLevelConfig(editState: GlobalConfigEditState): OperationResult<Unit> {
        val repo = configRepository ?: return Unit.right()
        val appConfig = repo.getCurrentConfig()
        val jobMap = editState.renewalJobs.associateBy { it.name }
        val defaults = SchedulerConfig()
        val schedulerCfg = SchedulerConfig(
            cliExecutablePath = editState.effectiveSchedulerExecutablePath,
            runAtHour = editState.schedulerHour.toIntOrNull()?.coerceIn(0, 23) ?: defaults.runAtHour,
            runAtMinute = editState.schedulerMinute.toIntOrNull()?.coerceIn(0, 59) ?: defaults.runAtMinute,
            logFilePath = editState.schedulerLogFile.trim().ifBlank { null },
            stalenessNotificationEnabled = editState.stalenessNotificationEnabled,
            stalenessThresholdDays = editState.stalenessThresholdDays.toIntOrNull()?.coerceAtLeast(1)
                ?: defaults.stalenessThresholdDays,
        )
        return repo.saveConfig(
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
     * @return A [SettingsError.SchedulerInstallFailed] when the scheduler install failed,
     *   or `null` on success (including the uninstall path).
     */
    private fun syncScheduler(editState: GlobalConfigEditState): SettingsError.SchedulerInstallFailed? {
        val port = schedulerPort ?: return null
        val exePath = editState.effectiveSchedulerExecutablePath
        if (editState.renewalJobs.isNotEmpty() && exePath != null) {
            return try {
                val defaults = SchedulerConfig()
                port.install(
                    cliExecutablePath = exePath,
                    runAtHour = editState.schedulerHour.toIntOrNull()?.coerceIn(0, 23) ?: defaults.runAtHour,
                    runAtMinute = editState.schedulerMinute.toIntOrNull()?.coerceIn(0, 59) ?: defaults.runAtMinute,
                    logFilePath = editState.schedulerLogFile.trim().ifBlank { null },
                )
                null
            } catch (e: Exception) {
                SettingsError.SchedulerInstallFailed(e.message)
            }
        } else {
            try {
                port.uninstall()
            } catch (_: Exception) { }
            return null
        }
    }

    /**
     * Whether the OS scheduler can register the renewal job on this system at all, as
     * [SchedulerPort.isAvailable] tells, asked on the [ioDispatcher] as finding out may start a
     * process: `false` without a [schedulerPort], and when asking fails.
     */
    private suspend fun schedulerAvailable(): Boolean = withContext(ioDispatcher) {
        try { schedulerPort?.isAvailable() == true } catch (_: Exception) { false }
    }

    /**
     * Whether the Backup section works: on the desktop both export and import, on the web only the
     * export of the server's configuration, as the dialog hides the import there.
     */
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
            ifLeft = { error -> _state.update { it.copy(error = SettingsError.Domain(error.localizableText())) }; null },
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
                ifLeft = { error -> _state.update { it.copy(error = SettingsError.Domain(error.localizableText())) } },
                ifRight = { load() },
            )
        }
    }

    companion object {
        private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
    }
}

