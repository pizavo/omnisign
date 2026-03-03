package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * JVM implementation of ConfigRepository using JSON files.
 *
 * Thread-safe: all reads and writes to the cached configuration are protected by a [Mutex].
 */
class FileConfigRepository(
    private val configPath: Path = getDefaultConfigPath()
) : ConfigRepository {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val mutex = Mutex()
    
    @Volatile
    private var cachedConfig: AppConfig? = null
    
    override suspend fun loadConfig(): OperationResult<AppConfig> = mutex.withLock {
        try {
            if (!configPath.exists()) {
                val defaultConfig = AppConfig(global = GlobalConfig())
                saveConfigInternal(defaultConfig)
                defaultConfig.right()
            } else {
                val configText = configPath.readText()
                val config = json.decodeFromString<AppConfig>(configText)
                cachedConfig = config
                config.right()
            }
        } catch (e: Exception) {
            ConfigurationError.LoadFailed(
                message = "Failed to load configuration",
                details = e.message,
                cause = e
            ).left()
        }
    }
    
    override suspend fun saveConfig(config: AppConfig): OperationResult<Unit> = mutex.withLock {
        saveConfigInternal(config)
    }
    
    /**
     * Internal save without acquiring [mutex]. Called by [loadConfig] (which already holds
     * the lock) when the config file does not exist yet.
     */
    private fun saveConfigInternal(config: AppConfig): OperationResult<Unit> {
        return try {
            configPath.parent?.toFile()?.mkdirs()
            val configText = json.encodeToString(config)
            configPath.writeText(configText)
            cachedConfig = config
            Unit.right()
        } catch (e: Exception) {
            ConfigurationError.SaveFailed(
                message = "Failed to save configuration",
                details = e.message,
                cause = e
            ).left()
        }
    }
    
    override suspend fun getCurrentConfig(): AppConfig {
        return cachedConfig ?: run {
            val result = loadConfig()
            result.fold(
                ifLeft = { AppConfig(global = GlobalConfig()) }, // Fallback to default
                ifRight = { it }
            )
        }
    }
    
    companion object {
        /**
         * Get the default configuration file path.
         * Uses platform-specific user config directory.
         */
        fun getDefaultConfigPath(): Path {
            val userHome = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            
            val configDir = when {
                os.contains("win") -> {
                    // Windows: %APPDATA%\omnisign
                    System.getenv("APPDATA")?.let { Paths.get(it, "omnisign") }
                        ?: Paths.get(userHome, "AppData", "Roaming", "omnisign")
                }
                os.contains("mac") -> {
                    // macOS: ~/Library/Application Support/omnisign
                    Paths.get(userHome, "Library", "Application Support", "omnisign")
                }
                else -> {
                    // Linux/Unix: ~/.config/omnisign
                    Paths.get(userHome, ".config", "omnisign")
                }
            }
            
            return configDir.resolve("config.json")
        }
    }
}

