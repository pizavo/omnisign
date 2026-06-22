package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.text.LocalizableText

/**
 * A user-facing error shown in a dialog's Error state, emitted by a view model as
 * locale-agnostic data; the UI resolves it to text.
 */
sealed interface ErrorMessage {
	/** A domain error carried as localizable [text] (resolved to the active locale by the UI) plus optional [details]. */
	data class Domain(val text: LocalizableText, val details: String?) : ErrorMessage

	/** Configuration could not be resolved; [text] is the localizable domain reason. */
	data class ConfigResolution(val text: LocalizableText) : ErrorMessage

	/** Writing the output document failed; [signed] true = signed doc, false = extended doc; [reason] is the platform error. */
	data class WriteFailed(val signed: Boolean, val reason: String?) : ErrorMessage

	/** Revocation data could not be refreshed during a B-LT extension; [domainDetails] is the DSS reason. */
	data class RevocationRefreshFailed(val domainDetails: String?) : ErrorMessage

	/** Trusted-list compilation is unavailable on this platform. */
	data object CompilerUnavailable : ErrorMessage
}
