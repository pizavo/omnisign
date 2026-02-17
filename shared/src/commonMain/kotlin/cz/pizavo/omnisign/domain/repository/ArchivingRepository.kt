package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Repository for archiving/LTA operations.
 */
interface ArchivingRepository {
    /**
     * Extend a signed document to B-LT or B-LTA format.
     *
     * @param parameters Archiving parameters
     * @return Archiving result or error
     */
    suspend fun extendToArchival(parameters: ArchivingParameters): OperationResult<ArchivingResult>
    
    /**
     * Check if a document needs archival timestamp renewal.
     *
     * @param filePath Path to the document
     * @return True if renewal is needed
     */
    suspend fun needsArchivalRenewal(filePath: String): OperationResult<Boolean>
}


