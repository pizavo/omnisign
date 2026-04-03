package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.validation.ValidationReport

/**
 * UI state for the Signature side panel.
 *
 * Transitions: [Idle] → [Loading] → [Loaded] or [Error].
 * Selecting a new PDF resets the state to [Idle].
 */
sealed interface SignaturePanelState {

    /**
     * No validation has been requested yet for the current document.
     *
     * @property hasDocument Whether a PDF document is currently loaded in the viewer.
     */
    data class Idle(val hasDocument: Boolean = false) : SignaturePanelState

    /**
     * Validation is currently in progress.
     */
    data object Loading : SignaturePanelState

    /**
     * Validation completed successfully.
     *
     * @property report The full validation report returned by DSS.
     */
    data class Loaded(val report: ValidationReport) : SignaturePanelState

    /**
     * Validation failed with an error.
     *
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : SignaturePanelState
}

