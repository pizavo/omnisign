package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for managing user-registered PKCS#11 middleware library paths.
 *
 * Registered libraries are stored in
 * [cz.pizavo.omnisign.domain.model.config.GlobalConfig.customPkcs11Libraries] and are merged
 * into token discovery on every [cz.pizavo.omnisign.domain.service.TokenService.discoverTokens]
 * call alongside the OS-native autodiscovery results.
 */
class ManagePkcs11LibrariesUseCase(
    private val configRepository: ConfigRepository,
) {
    /**
     * Register a PKCS#11 library entry.
     *
     * Replaces any existing entry with the same [CustomPkcs11Library.name].
     *
     * @param library The library entry to register.
     */
    suspend fun addLibrary(library: CustomPkcs11Library): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        val updated = current.copy(
            global = current.global.copy(
                customPkcs11Libraries = current.global.customPkcs11Libraries
                    .filter { it.name != library.name } + library,
            )
        )
        return configRepository.saveConfig(updated)
    }

    /**
     * Remove a registered PKCS#11 library entry by name.
     *
     * @param name The [CustomPkcs11Library.name] of the entry to remove.
     * @return An error if no entry with that name exists.
     */
    suspend fun removeLibrary(name: String): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        if (current.global.customPkcs11Libraries.none { it.name == name }) {
            return ConfigurationError.InvalidConfiguration(
                message = "No PKCS#11 library named '$name' is registered"
            ).left()
        }
        val updated = current.copy(
            global = current.global.copy(
                customPkcs11Libraries = current.global.customPkcs11Libraries
                    .filter { it.name != name },
            )
        )
        return configRepository.saveConfig(updated)
    }

    /**
     * Return all currently registered PKCS#11 library entries.
     */
    suspend fun listLibraries(): OperationResult<List<CustomPkcs11Library>> =
        configRepository.getCurrentConfig().global.customPkcs11Libraries.right()
}

