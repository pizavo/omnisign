package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.isWritable
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink

/**
 * JVM implementation of ConfigRepository using JSON files.
 *
 * The CLI, the desktop app and the scheduled renewal share the file, so every read, by
 * [getCurrentConfig] as much as by [loadConfig], reads it again: a change another process saved
 * reaches the next read instead of being hidden by an older copy, and a caller that reads the
 * configuration, changes it and saves it builds on the latest one.
 *
 * A [Mutex] orders the reads and writes of this instance, and [setActiveProfile] holds it from its
 * read to its save. A caller that reads with [getCurrentConfig] and saves with [saveConfig] does not
 * hold it in between, and no lock spans processes, so a change that another caller or another
 * process saves between such a read and its save is replaced by that save. Other processes are not
 * locked out, so every write replaces the file atomically through a temporary file in the same
 * directory: a reader in any process sees the previous configuration or the new one, never a
 * half-written file, and a crash or a full disk during a save leaves the previous file in place.
 * A configuration file that is a symbolic link stays one: the save replaces the file it leads to.
 * Windows refuses an atomic replace of a file that another program holds open, so a refused
 * replace is tried again for a moment before the save fails. As the temporary file is created
 * beside the file it replaces, a save needs write access to that directory, not only to the file.
 *
 * A configuration file that exists but cannot be decoded — a hand edit left it invalid, a write by
 * an older build was cut short, or a newer build wrote a value this one does not know — is never
 * replaced: [saveConfig] fails instead, because the file may hold profiles, renewal jobs and
 * settings that the configuration being saved lacks. That configuration was built on what
 * [getCurrentConfig] falls back to: the configuration this repository last read or saved, or the
 * defaults when it has none. The user fixes the file or moves it away, and the next save works.
 *
 * An error that an exception caused carries that exception as its cause. For an I/O failure its
 * details give the exception's class, since the message often holds only the path, as for a file
 * this user may not read or a replace that Windows refused; for any other failure, such as a file
 * that does not decode, they give the message.
 *
 * All file access runs on [ioDispatcher], so the repository is safe to call from a UI thread.
 *
 * @param configPath The configuration file.
 * @param ioDispatcher The dispatcher the file access runs on.
 */
class FileConfigRepository(
    private val configPath: Path = getDefaultConfigPath(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val mutex = Mutex()

    /**
     * The configuration this repository last read from the file or saved to it, which a read that
     * fails falls back to. Read and written only while [mutex] is held.
     */
    private var lastLoaded: AppConfig? = null

    /**
     * Load the configuration from the file, creating the file with the defaults when it does not
     * exist yet; the defaults are returned even when that write fails. A file that cannot be read
     * or decoded is left as it is.
     */
    override suspend fun loadConfig(): OperationResult<AppConfig> = withContext(ioDispatcher) {
        mutex.withLock { loadLocked() }
    }

    /**
     * [loadConfig] for a caller that already holds [mutex].
     */
    private fun loadLocked(): OperationResult<AppConfig> =
        try {
            if (!configPath.exists()) {
                val defaultConfig = AppConfig(global = GlobalConfig())
                saveConfigInternal(defaultConfig)
                defaultConfig.right()
            } else {
                val config = json.decodeFromString<AppConfig>(configPath.readConfigText())
                lastLoaded = config
                config.right()
            }
        } catch (e: Exception) {
            ConfigurationError.loadFailed(details = e.failureDetails(), cause = e).left()
        }

    /**
     * Save [config] over the configuration file, unless the file exists and cannot be read or
     * decoded, or is read-only: either way the file is left as it is and an error says why.
     */
    override suspend fun saveConfig(config: AppConfig): OperationResult<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            saveConfigInternal(config)
        }
    }

    /**
     * [saveConfig] for a caller that already holds [mutex]: [loadLocked] creating a missing file,
     * and [setActiveProfile].
     */
    private fun saveConfigInternal(config: AppConfig): OperationResult<Unit> {
        if (configPath.exists()) {
            unreadableReason()?.let { reason ->
                return ConfigurationError.unreadableNotOverwritten(
                    path = configPath.toAbsolutePath().toString(),
                    details = reason.failureDetails(),
                    cause = reason,
                ).left()
            }
            if (!configPath.isWritable()) {
                return ConfigurationError.readOnly(configPath.toAbsolutePath().toString()).left()
            }
        }
        return try {
            writeAtomically(json.encodeToString(config).encodeToByteArray())
            lastLoaded = config
            Unit.right()
        } catch (e: Exception) {
            ConfigurationError.saveFailed(details = e.failureDetails(), cause = e).left()
        }
    }

    /**
     * Why the existing configuration file cannot be loaded, or `null` when it reads and decodes.
     */
    private fun unreadableReason(): Exception? =
        try {
            json.decodeFromString<AppConfig>(configPath.readConfigText())
            null
        } catch (e: Exception) {
            e
        }

    /**
     * Replace the configuration file with [bytes] in one step.
     *
     * The bytes are written and forced to disk in a temporary file beside the file being replaced,
     * created owner-only on POSIX file systems, and then moved over it, atomically where the file
     * system supports that. The temporary file shares the directory so that the move stays on one
     * file system, and is removed again when a step fails. When the configuration file is a
     * symbolic link, the file it leads to is replaced and the link is kept, as the in-place write
     * this replaced did.
     */
    private fun writeAtomically(bytes: ByteArray) {
        val target = saveTarget()
        val directory = target.parent
        directory.createDirectories()
        val tempFile = createTempFile(directory, ".${target.name}.", ".tmp")
        try {
            FileChannel.open(tempFile, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            restrictPermissions(tempFile)
            replace(target, tempFile)
        } finally {
            tempFile.deleteIfExists()
        }
    }

    /**
     * Move [replacement] over [target], atomically where the file system supports that.
     *
     * Windows refuses an atomic replace of a file that another program holds open, and a reader of
     * the configuration in another OmniSign process, a virus scanner or a search indexer can hold it
     * for a moment. A refused move is therefore tried again after a pause that doubles each time, up
     * to [REPLACE_ATTEMPTS] attempts in all; the last refusal is thrown.
     */
    private fun replace(target: Path, replacement: Path) {
        var attempt = 1
        while (true) {
            try {
                try {
                    replacement.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    replacement.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: FileSystemException) {
                if (attempt == REPLACE_ATTEMPTS) throw e
                Thread.sleep(FIRST_RETRY_DELAY_MS shl (attempt - 1))
                attempt++
            }
        }
    }

    /**
     * The file a save replaces: the configuration file itself, or the file its chain of symbolic
     * links ends at, which may not exist yet.
     */
    private fun saveTarget(): Path {
        var target = configPath.toAbsolutePath()
        var links = 0
        while (target.isSymbolicLink()) {
            check(++links <= MAX_SYMBOLIC_LINKS) { "Too many levels of symbolic links at $configPath" }
            target = target.resolveSibling(target.readSymbolicLink())
        }
        return target
    }

    /**
     * Restrict the file at [path] to owner-read and owner-write (POSIX 600).
     *
     * Silently ignored on non-POSIX file systems (e.g. Windows NTFS) where
     * [PosixFilePermission] is not supported.
     */
    private fun restrictPermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
        }
    }

    /**
     * The configuration the file holds now, read again on every call and created with the defaults
     * when the file does not exist.
     *
     * When the file cannot be read or decoded, returns the configuration this repository last read
     * or saved, or the defaults when it has none, and leaves the file as it is; [saveConfig] refuses
     * to replace such a file. Falling back to the last configuration rather than to the defaults
     * keeps a read that fails only for a moment, as while another program holds the file, from
     * handing a caller defaults that it would then save over the file.
     */
    override suspend fun getCurrentConfig(): AppConfig = withContext(ioDispatcher) {
        mutex.withLock { currentLocked() }
    }

    /**
     * [getCurrentConfig] for a caller that already holds [mutex].
     */
    private fun currentLocked(): AppConfig =
        loadLocked().getOrElse { lastLoaded ?: AppConfig(global = GlobalConfig()) }

    /**
     * Save the active-profile selection into the configuration file, holding [mutex] from the read
     * of the file to the save, so that no save of this repository comes between them. Fails like
     * [saveConfig] when the file cannot be read, leaving it as it is.
     */
    override suspend fun setActiveProfile(name: String?): OperationResult<Unit> = withContext(ioDispatcher) {
        mutex.withLock { saveConfigInternal(currentLocked().copy(activeProfile = name)) }
    }

    companion object {
        /** How many symbolic links a save follows from the configuration file before it gives up. */
        private const val MAX_SYMBOLIC_LINKS = 40

        /** How many times a save tries to replace the configuration file before it gives up. */
        private const val REPLACE_ATTEMPTS = 6

        /** The pause before the first retry of a refused replace, in milliseconds; it doubles after each. */
        private const val FIRST_RETRY_DELAY_MS = 25L

        /**
         * Get the default configuration file path.
         * Uses the platform-specific user config directory resolved by [appConfigDirectory].
         */
        fun getDefaultConfigPath(): Path = appConfigDirectory().resolve("config.json")
    }
}
