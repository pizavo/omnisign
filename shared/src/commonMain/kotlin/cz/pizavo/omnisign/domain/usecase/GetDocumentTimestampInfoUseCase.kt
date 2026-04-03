package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository

/**
 * Performs a lightweight check of a PDF to determine its current timestamp and
 * signature level state.
 *
 * Used by the timestamp dialog to decide which extension options are valid
 * without requiring a full validation run.
 */
class GetDocumentTimestampInfoUseCase(
    private val archivingRepository: ArchivingRepository
) {
    /**
     * @param filePath Absolute path to the PDF document to inspect.
     * @return A [DocumentTimestampInfo] summarising the document state, or an error.
     */
    suspend operator fun invoke(filePath: String): OperationResult<DocumentTimestampInfo> =
        archivingRepository.getDocumentTimestampInfo(filePath)
}

