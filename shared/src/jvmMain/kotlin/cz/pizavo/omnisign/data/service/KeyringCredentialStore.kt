package cz.pizavo.omnisign.data.service

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * JVM implementation of [CredentialStore] backed by the OS native keychain.
 *
 * The resolution order is:
 * 1. **java-keyring** (Windows / macOS) — uses JNA to talk to the OS keychain natively
 *    (Windows Credential Manager, macOS Keychain).  The `dbus-java` transitive dependency
 *    is excluded at Gradle level, so the Linux backend is intentionally unavailable;
 *    Linux is handled by the next tier instead.
 * 2. **purejava/secret-service** (Linux) — communicates with the freedesktop Secret Service
 *    D-Bus API (GNOME Keyring, KDE Wallet, KeePassXC, …) via the
 *    [org.purejava:secret-service](https://github.com/purejava/secret-service) library,
 *    which is built against `dbus-java 5.x` (compatible with FileKit's transitive).
 * 3. **In-memory map** — last resort; credentials do not survive process restarts.
 *
 * A warning is logged at construction time when only the in-memory fallback is active.
 */
class KeyringCredentialStore : CredentialStore {

    private val keyring: Keyring? = try {
        Keyring.create()
    } catch (_: BackendNotSupportedException) {
        null
    }

    private val secretServiceFallback: FreedesktopSecretServiceStore? =
        if (keyring == null) {
            val store = FreedesktopSecretServiceStore()
            if (store.isAvailable) store else null
        } else {
            null
        }

    private val memoryFallback = mutableMapOf<String, String>()

    init {
        when {
            keyring != null ->
                logger.debug { "Credential store: java-keyring (native OS keychain)" }
            secretServiceFallback != null ->
                logger.debug {
                    "Credential store: purejava/secret-service (freedesktop Secret Service D-Bus API)"
                }
            else ->
                logger.warn {
                    "Native OS keychain is not available — credentials will be kept in memory only " +
                            "and will not survive process restarts. Install libsecret (Linux), or run " +
                            "in a desktop session with Keychain (macOS) or Credential Manager (Windows)."
                }
        }
    }

    /**
     * Whether a persistent credential backend is available (java-keyring or Secret Service).
     *
     * When `false` credentials are kept only in memory for the lifetime of the process.
     */
    val isNativeBackendAvailable: Boolean get() = keyring != null || secretServiceFallback != null

    override fun setPassword(service: String, account: String, password: String) {
        when {
            keyring != null -> keyring.setPassword(service, account, password)
            secretServiceFallback != null -> secretServiceFallback.setPassword(service, account, password)
            else -> memoryFallback["$service/$account"] = password
        }
    }

    override fun getPassword(service: String, account: String): String? {
        return when {
            keyring != null -> try {
                keyring.getPassword(service, account)
            } catch (_: PasswordAccessException) {
                null
            }
            secretServiceFallback != null -> secretServiceFallback.getPassword(service, account)
            else -> memoryFallback["$service/$account"]
        }
    }

    override fun deletePassword(service: String, account: String) {
        when {
            keyring != null -> try {
                keyring.deletePassword(service, account)
            } catch (_: PasswordAccessException) {
                // Entry did not exist; nothing to do.
            }
            secretServiceFallback != null -> secretServiceFallback.deletePassword(service, account)
            else -> memoryFallback.remove("$service/$account")
        }
    }
}
