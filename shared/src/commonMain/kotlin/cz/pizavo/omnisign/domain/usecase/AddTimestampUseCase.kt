package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.TimestampingResult
import cz.pizavo.omnisign.domain.model.parameters.TimestampParameters
import cz.pizavo.omnisign.domain.repository.TimestampRepository

/**
 * Use case for adding timestamps to documents.
 */
class AddTimestampUseCase(
    private val timestampRepository: TimestampRepository
) {
    /**
     * Execute timestamp addition.
     *
     * @param parameters Timestamp parameters
     * @return Timestamping result or error
     */
    suspend operator fun invoke(parameters: TimestampParameters): OperationResult<TimestampingResult> {
        return timestampRepository.addTimestamp(parameters)
    }
}


