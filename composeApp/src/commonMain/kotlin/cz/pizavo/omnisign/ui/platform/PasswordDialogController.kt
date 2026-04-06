package cz.pizavo.omnisign.ui.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Pending password request exposed to the Compose UI layer.
 *
 * @property prompt Message shown to the user describing what the password is for.
 * @property title Dialog title text.
 */
data class PasswordDialogRequest(
	val prompt: String,
	val title: String,
)

/**
 * Platform-agnostic controller for the password dialog.
 *
 * The Compose UI observes [request] and renders a [cz.pizavo.omnisign.ui.layout.PasswordDialog]
 * when non-null. On confirmation or cancellation the UI calls [complete] to dismiss
 * the dialog and unblock the background thread that initiated the request.
 *
 * The JVM implementation ([ComposePasswordCallback][cz.pizavo.omnisign.ui.platform.ComposePasswordCallback])
 * bridges this to the blocking [cz.pizavo.omnisign.platform.PasswordCallback] interface
 * expected by the DSS layer.
 */
interface PasswordDialogController {

	/**
	 * Observable password request.
	 *
	 * Non-null when a background operation needs the user to enter a password.
	 * The Compose layer should render the password dialog and call [complete]
	 * with the result.
	 */
	val request: StateFlow<PasswordDialogRequest?>

	/**
	 * Complete the current password request.
	 *
	 * @param password The password entered by the user, or `null` to signal cancellation.
	 */
	fun complete(password: String?)
}

