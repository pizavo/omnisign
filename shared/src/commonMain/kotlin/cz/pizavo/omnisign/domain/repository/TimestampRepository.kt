package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.TimestampingResult
import cz.pizavo.omnisign.domain.model.parameters.TimestampParameters

/**
 * Repository for timestamping operations.
 */
interface TimestampRepository {
    /**
     * Add a timestamp to a signed document.
     *
     * @param parameters Timestamp parameters
     * @return Timestamping result or error
     */
    suspend fun addTimestamp(parameters: TimestampParameters): OperationResult<TimestampingResult>
}


