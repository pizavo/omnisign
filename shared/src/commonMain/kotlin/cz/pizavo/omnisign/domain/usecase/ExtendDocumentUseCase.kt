package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository

/**
 * Extends an already-signed PDF to a higher PAdES level.
 *
 * Delegates to [ArchivingRepository.extendDocument], which covers all promotion paths:
 * B-B→T (add timestamp), B-T→LT (add revocation data), B-LT→LTA (add archival timestamp),
 * and B-LTA→LTA (archival renewal).
 */
class ExtendDocumentUseCase(
    private val archivingRepository: ArchivingRepository
) {
    /**
     * @param parameters Extension parameters, including the desired [cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel].
     * @return The archiving result or an error.
     */
    suspend operator fun invoke(parameters: ArchivingParameters): OperationResult<ArchivingResult> =
        archivingRepository.extendDocument(parameters)
}
