package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.model.config.requiredTrustedSourceIds
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ReportExportFormat
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.toPlainText
import cz.pizavo.omnisign.domain.model.validation.json.toJsonReport
import cz.pizavo.omnisign.domain.model.validation.json.toJsonString
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel driving the Signature side panel.
 *
 * Holds the current [SignaturePanelState] and triggers validation on demand.
 * When a new document is loaded via [onDocumentChanged] the state is reset to
 * [SignaturePanelState.Idle] so that stale results are never shown.
 *
 * @param validateDocumentUseCase Use case for validating a signed PDF.
 * @param configRepository Repository for retrieving the current application configuration
 *   so that EU LOTL and custom trusted lists are applied during validation. On the JVM
 *   it loads from the local config store; on the web target the
 *   [cz.pizavo.omnisign.data.remote.RemoteConfigRepository] returns a sanitized view
 *   of the server's `signing.yml`.
 * @param ioDispatcher Dispatcher used for the heavy validation work. Defaults to
 *   [Dispatchers.Default]; tests should substitute a [kotlinx.coroutines.test.StandardTestDispatcher].
 * @param trustedListRefreshPort Optional refresh signal. When a refresh of a trusted
 *   source this document's active configuration needs is in flight, validation is
 *   blocked until it completes. `null` on targets without a DSS backend (web), where
 *   nothing is ever refreshing.
 */
class SignatureViewModel(
    private val validateDocumentUseCase: ValidateDocumentUseCase,
    private val configRepository: ConfigRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val trustedListRefreshPort: TrustedListRefreshPort? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<SignaturePanelState>(SignaturePanelState.Idle())

    /** Observable panel state. */
    val state: StateFlow<SignaturePanelState> = _state.asStateFlow()

    /**
     * Trusted-source ids the active configuration needs, kept current so
     * [validationBlocked] only reacts to refreshes that would actually contend
     * with a validation of the current selection.
     */
    private val _requiredIds = MutableStateFlow<Set<TrustedSourceId>>(emptySet())

    /**
     * `true` while a refresh of a trusted source this configuration depends on is
     * in flight (global only, or global + the active profile's lists). The verify
     * action is disabled until it clears. Always `false` when no refresh port is
     * available (web).
     */
    val validationBlocked: StateFlow<Boolean> =
        trustedListRefreshPort?.let { port ->
            combine(port.running, _requiredIds) { running, required ->
                required.isNotEmpty() && running.any { it in required }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        } ?: MutableStateFlow(false)

    /**
     * Live progress of loading the trusted lists (EU LOTL members + custom lists), driving the
     * panel's determinate bar while [validationBlocked]. Idle when no refresh port is available (web).
     */
    val trustedListLoadProgress: StateFlow<TrustedListLoadProgress> =
        trustedListRefreshPort?.trustedListLoadProgress ?: MutableStateFlow(TrustedListLoadProgress())

    init {
        viewModelScope.launch { resolveRequiredIds() }
    }

    /**
     * Resolve the active configuration and publish the trusted-source ids it
     * needs. Failures resolve to an empty set (nothing to wait for).
     */
    private suspend fun resolveRequiredIds() {
        val appConfig = configRepository.getCurrentConfig()
        val resolved = ResolvedConfig.resolve(
            global = appConfig.global,
            profile = appConfig.activeProfile?.let { appConfig.profiles[it] },
            operationOverrides = null,
        ).getOrNull()
        _requiredIds.value = resolved?.requiredTrustedSourceIds() ?: emptySet()
    }

    /** Currently loaded document, if any. */
    private var currentDocument: PdfDocumentInfo? = null

    /**
     * Return the list of [ReportExportFormat] entries that can be used for the
     * current report. Domain-level formats (TXT, JSON) are always available;
     * raw DSS XML formats are available only when the report carries them.
     */
    fun availableExportFormats(): List<ReportExportFormat> {
        val loaded = _state.value as? SignaturePanelState.Loaded ?: return emptyList()
        return ReportExportFormat.entries.filter { format ->
            val raw = format.rawReportFormat
            raw == null || loaded.report.rawReports.containsKey(raw)
        }
    }

    /**
     * Export the current [ValidationReport] in the requested [format].
     *
     * Returns the serialized string or `null` when no report is loaded or the
     * requested raw XML format is not available.
     */
    fun exportReport(format: ReportExportFormat): String? {
        val loaded = _state.value as? SignaturePanelState.Loaded ?: return null
        val report = loaded.report

        return when (format) {
            ReportExportFormat.TXT -> report.toPlainText()
            ReportExportFormat.JSON -> report.toJsonReport().toJsonString()
            else -> {
                val rawKey = format.rawReportFormat ?: return null
                report.rawReports[rawKey]
            }
        }
    }

    /**
     * Suggested default file name (without the format's [extension][ReportExportFormat.extension])
     * for exporting the current report in [format], derived from the validated document's name.
     * Returns `null` when no report is loaded.
     */
    fun suggestedReportFileName(format: ReportExportFormat): String? {
        val loaded = _state.value as? SignaturePanelState.Loaded ?: return null
        return format.suggestedBaseName(loaded.report.documentName)
    }

    /**
     * Notify the ViewModel that a new PDF document has been loaded (or cleared).
     *
     * Resets the panel to [SignaturePanelState.Idle].
     *
     * @param document Newly loaded document holding its in-memory bytes and name, or
     *   `null` when no document is open.
     */
    fun onDocumentChanged(document: PdfDocumentInfo?) {
        currentDocument = document
        _state.update { SignaturePanelState.Idle(hasDocument = document != null) }
    }

    /**
     * Request signature information for the currently loaded document.
     *
     * No-op when there is no document loaded or when a load is already in progress.
     */
    fun loadSignatures() {
        val document = currentDocument ?: return
        if (_state.value is SignaturePanelState.Loading) return
        if (validationBlocked.value) return

        _state.update { SignaturePanelState.Loading }
        viewModelScope.launch {
            var alertIfNotEuLotl = false
            val result = withContext(ioDispatcher) {
                val appConfig = configRepository.getCurrentConfig()
                val resolvedConfig = ResolvedConfig.resolve(
                    global = appConfig.global,
                    profile = appConfig.activeProfile?.let { appConfig.profiles[it] },
                    operationOverrides = null,
                ).getOrNull()
                _requiredIds.value = resolvedConfig?.requiredTrustedSourceIds() ?: emptySet()
                alertIfNotEuLotl = resolvedConfig?.validation?.let { it.useEuLotl && it.alertIfNotEuLotl == true } == true
                validateDocumentUseCase(
                    ValidationParameters(
                        inputBytes = document.data,
                        inputName = document.name,
                        resolvedConfig = resolvedConfig,
                        profileName = appConfig.activeProfile,
                        rawReportFormats = RawReportFormat.entries.toSet(),
                    )
                )
            }
            result.fold(
                ifLeft = { error ->
                    _state.update {
                        SignaturePanelState.Error(
                            message = error.message,
                        )
                    }
                },
                ifRight = { report ->
                    _state.update { SignaturePanelState.Loaded(report, alertIfNotEuLotl = alertIfNotEuLotl) }
                },
            )
        }
    }

    /**
     * Serialize the current [ValidationReport] to a human-readable text representation
     * suitable for saving to a file. Returns `null` when no report is available.
     */
    fun exportReportText(): String? = exportReport(ReportExportFormat.TXT)
}


