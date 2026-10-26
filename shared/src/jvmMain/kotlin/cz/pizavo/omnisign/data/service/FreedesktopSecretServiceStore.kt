package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.service.CredentialStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.DBusPath
import org.purejava.secret.api.Collection
import org.purejava.secret.api.DBusMessageHandler.DBusResult
import org.purejava.secret.api.EncryptedSession
import org.purejava.secret.api.Item
import org.purejava.secret.api.Service

private val logger = KotlinLogging.logger {}

/**
 * Linux [CredentialStore] implementation that communicates with the freedesktop
 * Secret Service D-Bus API (GNOME Keyring, KDE Wallet, KeePassXC, …) via the
 * [org.purejava:secret-service](https://github.com/purejava/secret-service) library.
 *
 * This library is built against `dbus-java 5.x`, which is compatible with the
 * `dbus-java` version transitively brought in by FileKit.
 *
 * Items are stored in the **default collection** (`/org/freedesktop/secrets/aliases/default`)
 * and identified by `service` + `account` attributes, matching the same attribute
 * schema that `secret-tool` uses.
 *
 * @see KeyringCredentialStore
 */
class FreedesktopSecretServiceStore : CredentialStore {

    /**
     * Whether the D-Bus Secret Service is reachable.
     *
     * Checked once at construction time. When `false`, all operations silently
     * no-op (the caller is expected to fall back to an in-memory store).
     */
    val isAvailable: Boolean = checkAvailable()

    /** Passwords are kept by the Secret Service provider, so they persist whenever it is [isAvailable]. */
    override val isPersistent: Boolean get() = isAvailable

    override fun setPassword(service: String, account: String, password: String) {
        if (!isAvailable) return

        try {
            withEncryptedSession { session ->
                val defaultCollection = defaultCollection() ?: return@withEncryptedSession
                val existing = findItemPath(defaultCollection, service, account)

                if (existing != null) {
                    val secret = session.encrypt(password)
                    Item(existing).setSecret(secret)
                    secret.clear()
                } else {
                    val attributes = credentialAttributes(service, account)
                    val properties = Item.createProperties("$service/$account", attributes)
                    val secret = session.encrypt(password)
                    defaultCollection.createItem(properties, secret, true)
                    secret.clear()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to store credential via Secret Service for service=$service account=$account" }
        }
    }

    override fun getPassword(service: String, account: String): String? {
        if (!isAvailable) return null

        return try {
            withEncryptedSession { session ->
                val defaultCollection = defaultCollection() ?: return@withEncryptedSession null
                val itemPath = findItemPath(defaultCollection, service, account)
                    ?: return@withEncryptedSession null

                val secret = Item(itemPath).getSecret(session.session)
                val chars = session.decrypt(secret)
                val result = String(chars)
                secret.clear()
                result
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to lookup credential via Secret Service for service=$service account=$account" }
            null
        }
    }

    override fun deletePassword(service: String, account: String) {
        if (!isAvailable) return

        try {
            val defaultCollection = defaultCollection() ?: return
            val itemPath = findItemPath(defaultCollection, service, account) ?: return
            Item(itemPath).delete()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete credential via Secret Service for service=$service account=$account" }
        }
    }

    private fun credentialAttributes(service: String, account: String): Map<String, String> =
        mapOf("service" to service, "account" to account)

    private fun defaultCollection(): Collection? {
        val svc = Service()
        val alias = svc.readAlias("default").valueOrNull()
        if (alias == null || alias.path == "/") return null

        val collection = Collection(alias)
        if (collection.isLocked().valueOrNull() == true) {
            svc.ensureUnlocked(alias)
        }
        return collection
    }

    private fun findItemPath(collection: Collection, service: String, account: String): DBusPath? {
        val attributes = credentialAttributes(service, account)
        return collection.searchItems(attributes).valueOrNull()?.firstOrNull()
    }

    /**
     * The value of a successful D-Bus call, or `null` when the call failed — every caller treats
     * a failed call the same as a missing value.
     */
    private fun <T> DBusResult<T>.valueOrNull(): T? = when (this) {
        is DBusResult.Success -> value()
        is DBusResult.Failure -> null
    }

    private fun <T> withEncryptedSession(block: (EncryptedSession) -> T): T {
        val session = EncryptedSession()
        session.initialize()
        session.openSession()
        session.generateSessionKey()
        try {
            return block(session)
        } finally {
            session.clear()
        }
    }

    private companion object {
        /**
         * Probes whether the freedesktop Secret Service is reachable on the session D-Bus.
         */
        fun checkAvailable(): Boolean = try {
            Service().isAvailable
        } catch (_: Throwable) {
            false
        }
    }
}

