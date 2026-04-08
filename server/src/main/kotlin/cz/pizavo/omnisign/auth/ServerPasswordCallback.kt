package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.platform.PasswordCallback

/**
 * Server-side [PasswordCallback] that always returns `null`.
 *
 * In a headless server environment there is no interactive user to provide a PIN or
 * password. Operations that require a token password should be pre-configured via the
 * application config or the credential store.
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

