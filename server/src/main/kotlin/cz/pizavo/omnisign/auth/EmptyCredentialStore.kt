package cz.pizavo.omnisign.auth

import arrow.core.left
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.service.CredentialStore

/**
 * Server-side [CredentialStore] that holds no passwords and keeps none.
 *
 * A server that does not sign has nothing to look up: TSA passwords come from the environment,
 * since the signing policy rejects a keyring `credentialKey`, and token PINs are only needed to
 * sign. Binding this store in place of the keyring-backed one keeps such a server from opening the
 * host's keychain at all — no D-Bus probe in a container, and no reading of the service account's
 * entries.
 */
class EmptyCredentialStore : CredentialStore {

	/** Nothing is ever kept, so nothing persists. */
	override val isPersistent: Boolean = false

	/**
	 * Discards the password and says so; this store keeps none.
	 *
	 * @param service Ignored.
	 * @param account Ignored.
	 * @param password Ignored.
	 * @return Always a [ConfigurationError.SaveFailed], because the password was not stored.
	 */
	override fun setPassword(service: String, account: String, password: String): OperationResult<Unit> =
		ConfigurationError.passwordStoreFailed("This server keeps no passwords").left()

	/**
	 * Always returns `null`, because this store holds no passwords.
	 *
	 * @param service Ignored.
	 * @param account Ignored.
	 * @return Always `null`.
	 */
	override fun getPassword(service: String, account: String): String? = null

	/**
	 * Does nothing; there is no entry to remove.
	 *
	 * @param service Ignored.
	 * @param account Ignored.
	 */
	override fun deletePassword(service: String, account: String) = Unit
}
