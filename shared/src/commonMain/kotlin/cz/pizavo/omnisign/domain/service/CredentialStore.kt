package cz.pizavo.omnisign.domain.service

/**
 * Abstraction over a platform-native secure credential store (OS keychain).
 *
 * Implementations are expected to delegate to the OS keychain:
 * - Windows: Credential Manager
 * - macOS: Keychain
 * - Linux: libsecret / GNOME Keyring
 *
 * All operations are keyed by a [service] name and an [account] identifier so
 * that multiple credentials for the same service can be stored independently.
 */
interface CredentialStore {
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

