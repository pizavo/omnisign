package cz.pizavo.omnisign.data.repository

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
import kotlin.io.path.readText

/**
 * JVM implementation of ConfigRepository using JSON files.
 *
 * A [Mutex] orders the reads and writes of this instance, so callers within one process never
 * interleave. Other processes — the CLI, the desktop app and the scheduled renewal share the file —
 * are not locked out, so every write replaces the file atomically through a temporary file in the
 * same directory: a reader in any process sees the previous configuration or the new one, never a
 * half-written file, and a crash or a full disk during a save leaves the previous file in place.
 * A configuration file that is a symbolic link stays one: the save replaces the file it leads to.
 *
 * A configuration file that exists but cannot be decoded — a hand edit left it invalid, a write by
 * an older build was cut short, or a newer build wrote a value this one does not know — is never
 * replaced: [saveConfig] fails instead, because the file may hold profiles, renewal jobs and
 * settings that the configuration being saved, built on the defaults [getCurrentConfig] falls
 * back to, no longer contains. The user fixes the file or moves it away, and the next save works.
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

    @Volatile
    private var cachedConfig: AppConfig? = null

    /**
     * Load the configuration from the file, creating the file with the defaults when it does not
     * exist yet; the defaults are returned even when that write fails. A file that cannot be read
     * or decoded is left as it is.
     */
    override suspend fun loadConfig(): OperationResult<AppConfig> = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                if (!configPath.exists()) {
                    val defaultConfig = AppConfig(global = GlobalConfig())
                    saveConfigInternal(defaultConfig)
                    defaultConfig.right()
                } else {
                    val config = json.decodeFromString<AppConfig>(configPath.readText())
                    cachedConfig = config
                    config.right()
                }
            } catch (e: Exception) {
                ConfigurationError.loadFailed(details = e.message, cause = e).left()
            }
        }
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
     * Internal save without acquiring [mutex]. Called by [loadConfig] (which already holds
     * the lock) when the config file does not exist yet.
     */
    private fun saveConfigInternal(config: AppConfig): OperationResult<Unit> {
        if (configPath.exists()) {
            unreadableReason()?.let { reason ->
                return ConfigurationError.unreadableNotOverwritten(
                    path = configPath.toAbsolutePath().toString(),
                    details = reason.message,
                    cause = reason,
                ).left()
            }
            if (!configPath.isWritable()) {
                return ConfigurationError.readOnly(configPath.toAbsolutePath().toString()).left()
            }
        }
        return try {
            writeAtomically(json.encodeToString(config).encodeToByteArray())
            cachedConfig = config
            Unit.right()
        } catch (e: Exception) {
            ConfigurationError.saveFailed(details = e.message, cause = e).left()
        }
    }

    /**
     * Why the existing configuration file cannot be loaded, or `null` when it reads and decodes.
     */
    private fun unreadableReason(): Exception? =
        try {
            json.decodeFromString<AppConfig>(configPath.readText())
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
            try {
                tempFile.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                tempFile.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.deleteIfExists()
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
     * The cached configuration, loading it first when nothing is cached.
     *
     * When the file cannot be read, returns the default configuration without caching it, so the
     * next call tries the file again; [saveConfig] refuses to replace such a file.
     */
    override suspend fun getCurrentConfig(): AppConfig {
        return cachedConfig ?: run {
            val result = loadConfig()
            result.fold(
                ifLeft = { AppConfig(global = GlobalConfig()) },
                ifRight = { it }
            )
        }
    }

    /**
     * Save the active-profile selection into the configuration file. Fails like [saveConfig]
     * when the file cannot be read, leaving it as it is.
     */
    override suspend fun setActiveProfile(name: String?): OperationResult<Unit> {
        val current = getCurrentConfig()
        return saveConfig(current.copy(activeProfile = name))
    }

    companion object {
        /** How many symbolic links a save follows from the configuration file before it gives up. */
        private const val MAX_SYMBOLIC_LINKS = 40

        /**
         * Get the default configuration file path.
         * Uses the platform-specific user config directory resolved by [appConfigDirectory].
         */
        fun getDefaultConfigPath(): Path = appConfigDirectory().resolve("config.json")
    }
}
