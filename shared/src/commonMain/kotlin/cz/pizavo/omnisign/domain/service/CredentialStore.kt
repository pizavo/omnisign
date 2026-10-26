package cz.pizavo.omnisign.domain.service

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
     * @param service Logical service name used as a namespace (e.g. "omnisign-tsa").
     * @param account Account / username to associate with the password.
     * @param password The secret to store.
     */
    fun setPassword(service: String, account: String, password: String)

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

