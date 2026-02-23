package cz.pizavo.omnisign.data.service

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import cz.pizavo.omnisign.domain.service.CredentialStore

/**
 * JVM implementation of [CredentialStore] backed by the OS native keychain via java-keyring.
 *
 * Falls back to an in-memory store when the native backend is unavailable (e.g. headless CI
 * environments). The fallback is intentionally non-persistent and will not survive process
 * restarts — callers should warn the user when the fallback is active.
 */
class KeyringCredentialStore : CredentialStore {

    private val keyring: Keyring? = try {
        Keyring.create()
    } catch (_: BackendNotSupportedException) {
        null
    }

    private val memoryFallback = mutableMapOf<String, String>()

    /**
     * Whether the native OS keychain backend is available.
     * When `false` credentials are kept only in memory for the lifetime of the process.
     */
    val isNativeBackendAvailable: Boolean get() = keyring != null

    override fun setPassword(service: String, account: String, password: String) {
        if (keyring != null) {
            keyring.setPassword(service, account, password)
        } else {
            memoryFallback["$service/$account"] = password
        }
    }

    override fun getPassword(service: String, account: String): String? {
        return if (keyring != null) {
            try {
                keyring.getPassword(service, account)
            } catch (_: PasswordAccessException) {
                null
            }
        } else {
            memoryFallback["$service/$account"]
        }
    }

    override fun deletePassword(service: String, account: String) {
        if (keyring != null) {
            try {
                keyring.deletePassword(service, account)
            } catch (_: PasswordAccessException) {
                // Entry did not exist; nothing to do.
            }
        } else {
            memoryFallback.remove("$service/$account")
        }
    }
}

