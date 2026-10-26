package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Abstraction over a platform-native secure credential store (OS keychain).
 *
 * Implementations are expected to delegate to the OS keychain:
 * - Windows: Credential Manager
 * - macOS: Keychain
 * - Linux: a freedesktop Secret Service provider over D-Bus (GNOME Keyring, KWallet, KeePassXC)
 *
 * All operations are keyed by a `service` name and an `account` identifier so
 * that multiple credentials for the same service can be stored independently.
 */
interface CredentialStore {
    /**
     * Whether a password stored here outlives the current process.
     *
     * `false` means the store keeps passwords in memory only, so one saved now is gone when the
     * process exits. A caller that records a reference to a stored password in the saved
     * configuration — a
     * [credentialKey][cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig.credentialKey] —
     * must not rely on such a store.
     */
    val isPersistent: Boolean

    /**
     * Store or update a password in the keychain.
     *
     * A caller that records a reference to the password in the saved configuration must do so only
     * once this has succeeded; a failure leaves any password stored before under the same
     * [service] and [account] in place.
     *
     * @param service Logical service name used as a namespace (e.g. "omnisign-tsa").
     * @param account Account / username to associate with the password.
     * @param password The secret to store.
     * @return `Unit` once the store keeps the password, or a
     *   [ConfigurationError.SaveFailed][cz.pizavo.omnisign.domain.model.error.ConfigurationError.SaveFailed]
     *   saying why it could not, such as a keychain that stays locked or refuses the write.
     */
    fun setPassword(service: String, account: String, password: String): OperationResult<Unit>

    /**
     * Retrieve a previously stored password.
     *
     * @param service Logical service name.
     * @param account Account / username.
     * @return The stored password, or `null` if no entry exists.
     */
    fun getPassword(service: String, account: String): String?

    /**
     * Remove a stored password entry.
     *
     * @param service Logical service name.
     * @param account Account / username.
     */
    fun deletePassword(service: String, account: String)
}

