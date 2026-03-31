package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.platform.PasswordCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Represents a pending password request that is displayed as a dialog in the Compose UI.
 *
 * @property prompt Message shown to the user describing what the password is for.
 * @property title Dialog title text.
 * @property deferred Deferred that is completed with the password or `null` when the user cancels.
 */
data class PasswordRequest(
	val prompt: String,
	val title: String,
	val deferred: CompletableDeferred<String?>,
)

/**
 * Compose Desktop implementation of [PasswordCallback].
 *
 * When the DSS layer needs a password (e.g. for a PKCS#12 keystore or a hardware token PIN),
 * it calls [requestPassword] from a background thread. This implementation posts a
 * [PasswordRequest] into a [StateFlow] that the Compose UI observes. A dialog is then rendered
 * and the background thread blocks (via [runBlocking]) until the user confirms or cancels.
 */
class ComposePasswordCallback : PasswordCallback {

	private val _request = MutableStateFlow<PasswordRequest?>(null)

	/** Observable password request. The Compose layer renders a dialog when this is non-null. */
	val request: StateFlow<PasswordRequest?> = _request.asStateFlow()

	/**
	 * Request a password from the user via a Compose dialog.
	 *
	 * This method blocks the calling thread until the dialog is dismissed.
	 *
	 * @param prompt Message describing what the password is for.
	 * @param title Dialog title text.
	 * @return The entered password, or `null` if the user cancelled.
	 */
	override fun requestPassword(prompt: String, title: String): String? {
		val deferred = CompletableDeferred<String?>()
		_request.value = PasswordRequest(prompt = prompt, title = title, deferred = deferred)
		return try {
			runBlocking { deferred.await() }
		} finally {
			_request.value = null
		}
	}

	/**
	 * Complete the current password request with the given value.
	 *
	 * @param password The password entered by the user, or `null` to signal cancellation.
	 */
	fun complete(password: String?) {
		_request.value?.deferred?.complete(password)
	}
}

