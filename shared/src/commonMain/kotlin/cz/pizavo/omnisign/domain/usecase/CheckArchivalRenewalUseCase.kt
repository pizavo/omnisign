package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository

/**
 * Use case for checking if document needs archival renewal.
 */
class CheckArchivalRenewalUseCase(
    private val archivingRepository: ArchivingRepository
) {
    /**
     * Check if document needs renewal.
     *
     * @param filePath Path to the document
     * @return True if renewal is needed, or error
     */
    suspend operator fun invoke(filePath: String): OperationResult<Boolean> {
        return archivingRepository.needsArchivalRenewal(filePath)
    }
}


