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
     * Stored configuration that cannot be read is never replaced: it may hold settings that
     * [config], built on the defaults [getCurrentConfig] falls back to, lacks, so the save fails
     * instead and leaves it as it is.
     *
     * @param config Configuration to save
     * @return Success or error
     */
    suspend fun saveConfig(config: AppConfig): OperationResult<Unit>

    /**
     * Get the current configuration, for an operation to run with or for a change to build on.
     *
     * Does not fail: when the stored configuration cannot be read, the default configuration is
     * returned, so that operations still run. A change built on those defaults cannot replace the
     * configuration that could not be read, because [saveConfig] refuses to.
     *
     * @return Current configuration
     */
    suspend fun getCurrentConfig(): AppConfig

    /**
     * Update the active-profile selection.
     *
     * Splits out of [saveConfig] because the implementations persist the selection in
     * different places: the JVM `FileConfigRepository` writes it to the on-disk
     * [AppConfig.activeProfile] alongside the rest of the configuration, while the web
     * `RemoteConfigRepository` writes it to a browser-side store and never round-trips
     * the selection to the server — the server is stateless for the active-profile
     * concern and only sees the selection on each individual operation request.
     *
     * @param name The profile name to activate, or `null` to clear the active profile.
     * @return [Unit] on success, or an error when the underlying persistence fails. Web
     *   implementations are expected to succeed for any in-range [name]; the validity of
     *   the name against the server's current profile list is the caller's concern.
     */
    suspend fun setActiveProfile(name: String?): OperationResult<Unit>
}

