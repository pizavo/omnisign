package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.disabledOverrideError
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.clearOrphanedProfileScopes
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right

/**
 * Use case for managing named configuration profiles.
 *
 * A profile's directly trusted certificates live in the [TrustStore] under a scope keyed by the profile's
 * name alone, so this use case keeps the two in step: removing a profile drops its scope, and creating one
 * first drops every scope no profile owns, so a new profile never starts out trusting what an earlier
 * profile of the same name was trusting.
 *
 * @param configRepository Store of the configuration the profiles belong to.
 * @param trustStore Store of the profiles' directly trusted certificates.
 */
class ManageProfileUseCase(
    private val configRepository: ConfigRepository,
    private val trustStore: TrustStore,
) {
    /**
     * Upsert a profile by name.
     *
     * Returns an error when [profile] disables its own [ProfileConfig.hashAlgorithm] or
     * [ProfileConfig.encryptionAlgorithm] override, since that would make every resolution
     * using the profile fail immediately, as [disabledOverrideError] describes.
     *
     * Before a profile of a new name is saved, every trust scope no profile owns is dropped, as
     * [clearOrphanedProfileScopes] describes, so the new profile does not inherit the certificates of a
     * removed profile of that name. When that fails, nothing is saved.
     *
     * @param profile The profile to add or replace.
     * @return Unit on success or an error.
     */
    suspend fun upsert(profile: ProfileConfig): OperationResult<Unit> {
        profile.disabledOverrideError()?.let { return it.left() }
        val current = configRepository.getCurrentConfig()
        if (profile.name !in current.profiles) {
            trustStore.clearOrphanedProfileScopes(current.profiles.keys).getOrElse { return it.left() }
        }
        val updated = current.copy(profiles = current.profiles + (profile.name to profile))
        return configRepository.saveConfig(updated)
    }

    /**
     * Remove a profile by name, together with its directly trusted certificates.
     *
     * Once the configuration without the profile is saved, every trust scope no profile owns is dropped,
     * the removed profile's among them, as [clearOrphanedProfileScopes] describes. When that fails, the
     * profile stays removed and the error is returned; the scope is dropped when a profile is next created.
     *
     * @param name The profile name to remove.
     * @return Unit on success or an error if the profile does not exist.
     */
    suspend fun remove(name: String): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        if (!current.profiles.containsKey(name)) {
            return ConfigurationError.profileNotFound(name).left()
        }
        val updated = current.copy(
            profiles = current.profiles - name,
            activeProfile = if (current.activeProfile == name) null else current.activeProfile
        )
        return configRepository.saveConfig(updated)
            .flatMap { trustStore.clearOrphanedProfileScopes(updated.profiles.keys) }
    }

    /**
     * Set the active profile.
     *
     * Delegates the actual persistence to [ConfigRepository.setActiveProfile] — on the JVM
     * that writes the new value into the on-disk [AppConfig.activeProfile], on the web target
     * that writes it to a browser-side store and never round-trips the selection through the
     * server.
     *
     * @param name The profile name to activate, or null to clear the active profile.
     * @return Unit on success or an error if the named profile does not exist.
     */
    suspend fun setActive(name: String?): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        if (name != null && !current.profiles.containsKey(name)) {
            return ConfigurationError.profileNotFound(name).left()
        }
        return configRepository.setActiveProfile(name)
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
            ?: ConfigurationError.profileNotFound(name).left()
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

