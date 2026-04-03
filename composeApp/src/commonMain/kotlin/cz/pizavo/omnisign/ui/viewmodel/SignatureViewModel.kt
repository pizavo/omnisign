package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ReportExportFormat
import cz.pizavo.omnisign.domain.model.validation.SignatureTrustTier
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.json.toJsonReport
import cz.pizavo.omnisign.domain.model.validation.json.toJsonString
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.ui.model.SignaturePanelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *   so that EU LOTL and custom trusted lists are applied during validation.
 * @param ioDispatcher Dispatcher used for the heavy validation work. Defaults to
 *   [Dispatchers.Default]; tests should substitute a [kotlinx.coroutines.test.StandardTestDispatcher].
 */
class SignatureViewModel(
    private val validateDocumentUseCase: ValidateDocumentUseCase,
    private val configRepository: ConfigRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<SignaturePanelState>(SignaturePanelState.Idle())

    /** Observable panel state. */
    val state: StateFlow<SignaturePanelState> = _state.asStateFlow()

    /** File path of the currently loaded document, if any. */
    private var currentFilePath: String? = null

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
            ReportExportFormat.TXT -> formatReport(report)
            ReportExportFormat.JSON -> report.toJsonReport().toJsonString()
            else -> {
                val rawKey = format.rawReportFormat ?: return null
                report.rawReports[rawKey]
            }
        }
    }

    /**
     * Notify the ViewModel that a new PDF document has been loaded (or cleared).
     *
     * Resets the panel to [SignaturePanelState.Idle].
     *
     * @param filePath Absolute path to the new document, or `null` when no document is open.
     */
    fun onDocumentChanged(filePath: String?) {
        currentFilePath = filePath
        _state.update { SignaturePanelState.Idle(hasDocument = filePath != null) }
    }

    /**
     * Request signature information for the currently loaded document.
     *
     * No-op when there is no document loaded or when a load is already in progress.
     */
    fun loadSignatures() {
        val path = currentFilePath ?: return
        if (_state.value is SignaturePanelState.Loading) return

        _state.update { SignaturePanelState.Loading }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val appConfig = configRepository.getCurrentConfig()
                val resolvedConfig = ResolvedConfig.resolve(
                    global = appConfig.global,
                    profile = appConfig.activeProfile?.let { appConfig.profiles[it] },
                    operationOverrides = null,
                ).getOrNull()
                validateDocumentUseCase(
                    ValidationParameters(inputFile = path, resolvedConfig = resolvedConfig)
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
                    _state.update { SignaturePanelState.Loaded(report) }
                },
            )
        }
    }

    /**
     * Serialize the current [ValidationReport] to a human-readable text representation
     * suitable for saving to a file. Returns `null` when no report is available.
     */
    fun exportReportText(): String? = exportReport(ReportExportFormat.TXT)

    /**
     * Format a [ValidationReport] into a human-readable text string.
     */
    private fun formatReport(report: ValidationReport): String = buildString {
        appendLine("OmniSign — Validation Report")
        appendLine("════════════════════════════════════════")
        appendLine("Document:        ${report.documentName}")
        appendLine("Validation time: ${report.validationTime.formatDateTime()}")
        appendLine("Overall result:  ${report.overallResult}")
        if (report.overallTrustTier != SignatureTrustTier.NOT_QUALIFIED) {
            appendLine("Trust tier:      ${report.overallTrustTier.label}")
        }
        appendLine()

        if (report.signatures.isEmpty()) {
            appendLine("No signatures found in the document.")
        } else {
            report.signatures.forEachIndexed { index, sig ->
                appendLine("── Signature ${index + 1} of ${report.signatures.size} ──")
                appendLine("  Indication:     ${sig.indication}")
                sig.subIndication?.let { appendLine("  Sub-indication: $it") }
                appendLine("  Signed by:      ${sig.signedBy}")
                appendLine("  Level:          ${sig.signatureLevel}")
                appendLine("  Time:           ${sig.signatureTime.formatDateTime()}")
                sig.signatureQualification?.let { appendLine("  Qualification:  $it") }
                if (sig.trustTier != SignatureTrustTier.NOT_QUALIFIED) {
                    appendLine("  Trust tier:     ${sig.trustTier.label}")
                }
                sig.hashAlgorithm?.let { appendLine("  Hash algorithm: $it") }
                sig.encryptionAlgorithm?.let { appendLine("  Encryption:     $it") }
                appendLine("  Certificate:")
                appendLine("    Subject:      ${sig.certificate.subjectDN}")
                appendLine("    Issuer:       ${sig.certificate.issuerDN}")
                appendLine("    Serial:       ${sig.certificate.serialNumber}")
                appendLine("    Valid from:   ${sig.certificate.validFrom.formatDate()}")
                appendLine("    Valid to:     ${sig.certificate.validTo.formatDate()}")
                if (sig.certificate.keyUsages.isNotEmpty()) {
                    appendLine("    Key usages:   ${sig.certificate.keyUsages.joinToString()}")
                }
                sig.certificate.publicKeyAlgorithm?.let { appendLine("    Public key:   $it") }
                sig.certificate.sha256Fingerprint?.let { appendLine("    SHA-256:      $it") }
                if (sig.errors.isNotEmpty()) {
                    appendLine("  Errors:")
                    sig.errors.forEach { appendLine("    • $it") }
                }
                if (sig.warnings.isNotEmpty()) {
                    appendLine("  Warnings:")
                    sig.warnings.forEach { appendLine("    • $it") }
                }
                appendLine()
            }
        }

        if (report.timestamps.isNotEmpty()) {
            appendLine("── Timestamps ──")
            report.timestamps.forEachIndexed { index, ts ->
                appendLine("  Timestamp ${index + 1}: ${ts.type}")
                appendLine("    Indication:      ${ts.indication}")
                ts.subIndication?.let { appendLine("    Sub-indication:  $it") }
                appendLine("    Production time: ${ts.productionTime.formatDateTime()}")
                ts.qualification?.let { appendLine("    Qualification:   $it") }
                ts.tsaSubjectDN?.let { appendLine("    TSA:             $it") }
                appendLine()
            }
        }

        if (report.tlWarnings.isNotEmpty()) {
            appendLine("── Trusted List Warnings ──")
            report.tlWarnings.forEach { appendLine("  ⚠ $it") }
        }
    }
}


