package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import arrow.core.left
import arrow.core.right

/**
 * Use case for managing named configuration profiles.
 */
class ManageProfileUseCase(
    private val configRepository: ConfigRepository
) {
    /**
     * Upsert a profile by name.
     *
     * @param profile The profile to add or replace.
     * @return Unit on success or an error.
     */
    suspend fun upsert(profile: ProfileConfig): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        val updated = current.copy(profiles = current.profiles + (profile.name to profile))
        return configRepository.saveConfig(updated)
    }

    /**
     * Remove a profile by name.
     *
     * @param name The profile name to remove.
     * @return Unit on success or an error if the profile does not exist.
     */
    suspend fun remove(name: String): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        if (!current.profiles.containsKey(name)) {
            return ConfigurationError.InvalidConfiguration(
                message = "Profile '$name' does not exist"
            ).left()
        }
        val updated = current.copy(
            profiles = current.profiles - name,
            activeProfile = if (current.activeProfile == name) null else current.activeProfile
        )
        return configRepository.saveConfig(updated)
    }

    /**
     * Set the active profile.
     *
     * @param name The profile name to activate, or null to clear the active profile.
     * @return Unit on success or an error if the named profile does not exist.
     */
    suspend fun setActive(name: String?): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        if (name != null && !current.profiles.containsKey(name)) {
            return ConfigurationError.InvalidConfiguration(
                message = "Profile '$name' does not exist"
            ).left()
        }
        val updated = current.copy(activeProfile = name)
        return configRepository.saveConfig(updated)
    }

    /**
     * Retrieve a single profile by name.
     *
     * @param name The profile name to look up.
     * @return The [ProfileConfig] on success or an error if the profile does not exist.
     */
    suspend fun get(name: String): OperationResult<ProfileConfig> {
        val current = configRepository.getCurrentConfig()
        return current.profiles[name]?.right()
            ?: ConfigurationError.InvalidConfiguration(
                message = "Profile '$name' does not exist"
            ).left()
    }

    /**
     * List all available profiles.
     *
     * @return Map of profile name to [ProfileConfig].
     */
    suspend fun list(): OperationResult<Map<String, ProfileConfig>> {
        val current = configRepository.getCurrentConfig()
        return current.profiles.right()
    }
}

