package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository

/**
 * Use case for extending documents to archival formats (B-LT/B-LTA).
 */
class ExtendToArchivalUseCase(
    private val archivingRepository: ArchivingRepository
) {
    /**
     * Execute archival extension.
     *
     * @param parameters Archiving parameters
     * @return Archiving result or error
     */
    suspend operator fun invoke(parameters: ArchivingParameters): OperationResult<ArchivingResult> {
        return archivingRepository.extendToArchival(parameters)
    }
}


