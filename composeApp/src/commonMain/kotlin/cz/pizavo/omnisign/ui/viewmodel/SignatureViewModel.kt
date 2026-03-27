package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.model.value.formatDateTime
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
 * @param ioDispatcher Dispatcher used for the heavy validation work. Defaults to
 *   [Dispatchers.Default]; tests should substitute a [kotlinx.coroutines.test.StandardTestDispatcher].
 */
class SignatureViewModel(
    private val validateDocumentUseCase: ValidateDocumentUseCase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<SignaturePanelState>(SignaturePanelState.Idle())

    /** Observable panel state. */
    val state: StateFlow<SignaturePanelState> = _state.asStateFlow()

    /** File path of the currently loaded document, if any. */
    private var currentFilePath: String? = null

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
                validateDocumentUseCase(ValidationParameters(inputFile = path))
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
    fun exportReportText(): String? {
        val loaded = _state.value as? SignaturePanelState.Loaded ?: return null
        return formatReport(loaded.report)
    }

    /**
     * Format a [ValidationReport] into a human-readable text string.
     */
    private fun formatReport(report: ValidationReport): String = buildString {
        appendLine("OmniSign — Validation Report")
        appendLine("════════════════════════════════════════")
        appendLine("Document:        ${report.documentName}")
        appendLine("Validation time: ${report.validationTime.formatDateTime()}")
        appendLine("Overall result:  ${report.overallResult}")
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


