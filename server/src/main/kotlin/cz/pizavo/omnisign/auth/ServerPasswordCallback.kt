package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.platform.PasswordCallback

/**
 * Server-side [PasswordCallback] that always returns `null`.
 *
 * In a headless server environment there is no interactive user to provide a PIN or
 * password. The PIN of a PIN-protected token must instead be saved in the host's keychain
 * (service `omnisign-token`, the token's id as the account), where a signing server's
 * credential store looks it up.
 */
class ServerPasswordCallback : PasswordCallback {

	/**
	 * Always returns `null` because the server cannot prompt interactively.
	 *
	 * @param prompt Ignored.
	 * @param title Ignored.
	 * @return Always `null`.
	 */
	override fun requestPassword(prompt: String, title: String): String? = null
}

