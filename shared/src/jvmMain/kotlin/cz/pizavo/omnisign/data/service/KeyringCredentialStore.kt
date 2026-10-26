package cz.pizavo.omnisign.data.service

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

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
 * Falling back to memory is logged at INFO when the store is built, and the first password kept
 * in memory logs a warning. A process that only reads passwords, or never touches the store at
 * all, therefore stays quiet where there is no keyring, such as in a container.
 */
class KeyringCredentialStore : CredentialStore {

    private val keyring: Keyring? = try {
        Keyring.create()
    } catch (_: BackendNotSupportedException) {
        null
    }

    private val secretServiceFallback: FreedesktopSecretServiceStore? =
        if (keyring == null) {
            try {
                val store = FreedesktopSecretServiceStore()
                if (store.isAvailable) store else null
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private val memoryFallback = mutableMapOf<String, String>()

    private val memoryUseWarned = AtomicBoolean(false)

    init {
        when {
            keyring != null ->
                logger.debug { "Credential store: java-keyring (native OS keychain)" }
            secretServiceFallback != null ->
                logger.debug {
                    "Credential store: purejava/secret-service (freedesktop Secret Service D-Bus API)"
                }
            else ->
                logger.info {
                    "Credential store: in memory only — no keyring service was found, so a password " +
                            "saved now is lost when the process exits"
                }
        }
    }

    /**
     * Whether a persistent credential backend is available (java-keyring or Secret Service).
     *
     * When `false` credentials are kept only in memory for the lifetime of the process.
     */
    override val isPersistent: Boolean get() = keyring != null || secretServiceFallback != null

    override fun setPassword(service: String, account: String, password: String) {
        when {
            keyring != null -> keyring.setPassword(service, account, password)
            secretServiceFallback != null -> secretServiceFallback.setPassword(service, account, password)
            else -> {
                warnOnceAboutMemoryUse()
                memoryFallback["$service/$account"] = password
            }
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

    /**
     * Warn, the first time only, that a password is going into memory and will not outlive the
     * process — the point at which the missing keyring costs the user something.
     */
    private fun warnOnceAboutMemoryUse() {
        if (!memoryUseWarned.compareAndSet(false, true)) return
        logger.warn {
            "No keyring service was found, so the password is kept in memory only and is lost when " +
                    "the process exits. On Linux, start a Secret Service provider such as GNOME Keyring, " +
                    "KWallet or KeePassXC."
        }
    }
}
