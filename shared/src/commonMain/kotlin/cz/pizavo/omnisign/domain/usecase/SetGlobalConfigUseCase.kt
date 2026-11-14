package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.disabledDefaultError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for updating the global (default) configuration.
 */
class SetGlobalConfigUseCase(
    private val configRepository: ConfigRepository
) {
    /**
     * Apply [update] to the current global config and persist it.
     *
     * Returns an error when the updated config would disable the currently selected
     * default hash or encryption algorithm, since that would make every subsequent
     * resolution fail immediately, as [disabledDefaultError] describes.
     *
     * @param update A transformation applied to the existing [GlobalConfig].
     * @return Unit on success or an error.
     */
    suspend operator fun invoke(update: GlobalConfig.() -> GlobalConfig): OperationResult<Unit> {
        val current = configRepository.getCurrentConfig()
        val updated = current.copy(global = current.global.update())
        updated.global.disabledDefaultError()?.let { return it.left() }
        return configRepository.saveConfig(updated)
    }
}

