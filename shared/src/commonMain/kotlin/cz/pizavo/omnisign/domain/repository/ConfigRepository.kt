package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.config.AppConfig

/**
 * Repository for configuration management.
 */
interface ConfigRepository {
    /**
     * Load application configuration.
     *
     * @return Configuration or error
     */
    suspend fun loadConfig(): OperationResult<AppConfig>
    
    /**
     * Save application configuration.
     *
     * @param config Configuration to save
     * @return Success or error
     */
    suspend fun saveConfig(config: AppConfig): OperationResult<Unit>
    
    /**
     * Get the current active configuration with resolved settings.
     *
     * @return Current configuration
     */
    suspend fun getCurrentConfig(): AppConfig
}

