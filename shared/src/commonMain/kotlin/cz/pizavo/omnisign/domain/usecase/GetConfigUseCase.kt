package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for retrieving the current application configuration.
 */
class GetConfigUseCase(
    private val configRepository: ConfigRepository
) {
    /**
     * Load and return the current application configuration.
     *
     * @return Current [AppConfig] or an error.
     */
    suspend operator fun invoke(): OperationResult<AppConfig> =
        configRepository.loadConfig()
}

