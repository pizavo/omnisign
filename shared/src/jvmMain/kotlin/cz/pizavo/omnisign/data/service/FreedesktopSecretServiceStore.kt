package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.service.CredentialStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.purejava.secret.api.Collection
import org.purejava.secret.api.DBusMessageHandler.DBusResult
import org.purejava.secret.api.EncryptedSession
import org.purejava.secret.api.Item
import org.purejava.secret.api.Prompt
import org.purejava.secret.api.Service
import org.purejava.secret.api.handlers.CompletedHandler
import kotlin.time.Duration.Companion.minutes

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
 * schema that `secret-tool` uses. Storing a password replaces the item that held the old one.
 *
 * A locked default collection is unlocked before it is used. When the provider wants the user to
 * confirm an operation, such as typing the keyring's password to unlock it, it shows its own prompt,
 * and the store waits for the answer for at most [PROMPT_TIMEOUT]; a prompt still open then is
 * dismissed, so a session that cannot show it does not hang. A failed operation is logged as a
 * warning, and otherwise treated as a missing value.
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
                val defaultCollection = defaultCollection() ?: run {
                    logger.warn {
                        "Did not store credential via Secret Service for service=$service account=$account: " +
                                "there is no unlocked default collection"
                    }
                    return@withEncryptedSession
                }
                val properties = Item.createProperties("$service/$account", credentialAttributes(service, account))
                val secret = session.encrypt(password)
                val result = try {
                    defaultCollection.createItem(properties, secret, true)
                } finally {
                    secret.clear()
                }

                when (result) {
                    is DBusResult.Success -> {
                        val item = result.value().a
                        val prompt = result.value().b
                        val stored = item.path != NO_OBJECT ||
                                (prompt.path != NO_OBJECT && !completePrompt(prompt).isNullOrEmpty())
                        if (!stored) {
                            logger.warn {
                                "Did not store credential via Secret Service for service=$service account=$account: " +
                                        "the provider did not create the item"
                            }
                        }
                    }

                    is DBusResult.Failure -> logger.warn(result.error()) {
                        "Failed to store credential via Secret Service for service=$service account=$account"
                    }
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

                val secret = Item(itemPath).getSecret(session.session) ?: return@withEncryptedSession null
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
            when (val result = Item(itemPath).delete()) {
                is DBusResult.Success -> {
                    val prompt = result.value()
                    if (prompt.path != NO_OBJECT && completePrompt(prompt) == null) {
                        logger.warn {
                            "Did not delete credential via Secret Service for service=$service account=$account: " +
                                    "the deletion was not confirmed"
                        }
                    }
                }

                is DBusResult.Failure -> logger.warn(result.error()) {
                    "Failed to delete credential via Secret Service for service=$service account=$account"
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete credential via Secret Service for service=$service account=$account" }
        }
    }

    /** The attributes that identify the item holding the password of [service] and [account]. */
    private fun credentialAttributes(service: String, account: String): Map<String, String> =
        mapOf("service" to service, "account" to account)

    /**
     * Return the collection the `default` alias points to, unlocking it when it is locked, or
     * `null` when there is no default collection or it stays locked.
     */
    private fun defaultCollection(): Collection? {
        val svc = Service()
        val alias = svc.readAlias("default").valueOrNull()
        if (alias == null || alias.path == NO_OBJECT) return null

        val collection = Collection(alias)
        if (collection.isLocked().valueOrNull() == true && !unlock(svc, collection, alias)) return null
        return collection
    }

    /**
     * Ask [svc] to unlock [collection], found at [path], going through the provider's prompt when
     * it shows one.
     *
     * @return Whether [collection] is unlocked now.
     */
    private fun unlock(svc: Service, collection: Collection, path: DBusPath): Boolean {
        when (val result = svc.unlock(listOf(path))) {
            is DBusResult.Success -> {
                val prompt = result.value().b
                if (prompt.path != NO_OBJECT) completePrompt(prompt)
            }

            is DBusResult.Failure -> {
                logger.warn(result.error()) { "Failed to unlock the Secret Service collection ${path.path}" }
                return false
            }
        }
        return collection.isLocked().valueOrNull() == false
    }

    /**
     * Show the provider's prompt at [path] and wait for the user to answer it, for at most
     * [PROMPT_TIMEOUT]; a prompt still open after that is dismissed.
     *
     * @return The paths of the objects the prompt returned, such as the collections it unlocked or
     *   the item it created, or `null` when it was dismissed or not answered in time.
     */
    private fun completePrompt(path: DBusPath): List<String>? {
        val prompt = Prompt(path)
        val answer = CompletableDeferred<List<String>?>()
        val handler = CompletedHandler { dismissed, result ->
            answer.complete(if (dismissed) null else objectPaths(result?.value))
        }
        prompt.addCompletedHandler(handler)
        try {
            prompt.prompt(PROMPT_WINDOW_ID)
            val paths = runBlocking { withTimeoutOrNull(PROMPT_TIMEOUT) { answer.await() } }
            when {
                !answer.isCompleted -> {
                    prompt.dismiss()
                    logger.warn {
                        "Dismissed the Secret Service prompt ${path.path}, which was not answered within $PROMPT_TIMEOUT"
                    }
                }

                paths == null -> logger.info { "The Secret Service prompt ${path.path} was dismissed" }
            }
            return paths
        } finally {
            prompt.removeCompletedHandler(handler)
        }
    }

    /** The object paths a prompt returned as its [result]: one path, a list of them, or none. */
    private fun objectPaths(result: Any?): List<String> = when (result) {
        is DBusPath -> listOf(result.path)
        is List<*> -> result.filterIsInstance<DBusPath>().map { it.path }
        else -> emptyList()
    }

    /** Find the item in [collection] that holds the password of [service] and [account]. */
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

    /** Run [block] in a newly opened encrypted session with the Secret Service, closing it after. */
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
        /** The path the Secret Service API uses for no object, such as a prompt that is not needed. */
        const val NO_OBJECT = "/"

        /**
         * The window the provider's prompt is shown for. `0` names none, so the prompt opens on its own;
         * the library refuses an empty one.
         */
        const val PROMPT_WINDOW_ID = "0"

        /** How long the store waits for the user to answer the provider's prompt before dismissing it. */
        val PROMPT_TIMEOUT = 2.minutes

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
