package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.platform.PasswordCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Compose Desktop implementation of [PasswordCallback] and [PasswordDialogController].
 *
 * When the DSS layer needs a password (e.g., for a PKCS#12 keystore or a hardware token PIN),
 * it calls [requestPassword] from a background thread. This implementation posts a
 * [PasswordDialogRequest] that the Compose UI observes via the [request] flow.
 * The background thread blocks (via [runBlocking]) until [complete] is called from the UI.
 */
class ComposePasswordCallback : PasswordCallback, PasswordDialogController {

	private val _request = MutableStateFlow<PasswordDialogRequest?>(null)

	override val request: StateFlow<PasswordDialogRequest?> = _request.asStateFlow()

	private var pendingDeferred: CompletableDeferred<String?>? = null

	/**
	 * Request a password from the user via a Compose dialog.
	 *
	 * This method blocks the calling thread until the dialog is dismissed.
	 *
	 * @param prompt Message describing what the password is for.
	 * @param title Dialog title text.
	 * @return The entered password, or `null` if the user canceled.
	 */
	override fun requestPassword(prompt: String, title: String): String? {
		val deferred = CompletableDeferred<String?>()
		pendingDeferred = deferred
		_request.value = PasswordDialogRequest(prompt = prompt, title = title)
		return try {
			runBlocking { deferred.await() }
		} finally {
			pendingDeferred = null
			_request.value = null
		}
	}

	/**
	 * Complete the current password request with the given value.
	 *
	 * @param password The password entered by the user, or `null` to signal cancellation.
	 */
	override fun complete(password: String?) {
		pendingDeferred?.complete(password)
	}
}

