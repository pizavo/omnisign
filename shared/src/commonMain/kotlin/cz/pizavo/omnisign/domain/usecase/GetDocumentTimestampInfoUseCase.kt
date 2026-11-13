package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
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
     * Inspect [inputBytes] for the timestamp and level state the extension options depend on.
     *
     * @param inputBytes Raw bytes of the PDF document to inspect.
     * @param resolvedConfig The configuration the extension will run with, whose trust anchors
     *   judge the document's level, or `null` to use the active profile's.
     * @return A [DocumentTimestampInfo] summarising the document state, or an error.
     */
    suspend operator fun invoke(
        inputBytes: ByteArray,
        resolvedConfig: ResolvedConfig? = null,
    ): OperationResult<DocumentTimestampInfo> =
        archivingRepository.getDocumentTimestampInfo(inputBytes, resolvedConfig)
}

