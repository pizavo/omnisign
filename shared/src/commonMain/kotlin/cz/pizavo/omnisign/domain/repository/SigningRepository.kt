package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.SigningResult

/**
 * Repository for document signing operations.
 */
interface SigningRepository {
    /**
     * Sign a document with the specified parameters.
     *
     * @param parameters Signing parameters
     * @return Signing result or error
     */
    suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult>
    
    /**
     * List available certificates from configured token sources.
     *
     * @return List of certificate aliases or error
     */
    suspend fun listAvailableCertificates(): OperationResult<List<AvailableCertificateInfo>>
}


