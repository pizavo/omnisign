package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.text.LocalizableText
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
     * @property alertIfNotEuLotl Effective "alert if not on EU LOTL" flag — true only when EU LOTL
     *   usage is also enabled; flags signatures whose trust anchor is not on the EU LOTL.
     */
    data class Loaded(
        val report: ValidationReport,
        val alertIfNotEuLotl: Boolean = false,
    ) : SignaturePanelState

    /**
     * Validation failed with an error.
     *
     * @property text Locale-agnostic error text the UI resolves to display text.
     */
    data class Error(val text: LocalizableText) : SignaturePanelState
}

