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
     * List available certificates from all configured token sources.
     *
     * Per-token access failures are not propagated as hard errors; they are collected in
     * [CertificateDiscoveryResult.tokenWarnings] so callers can surface diagnostic information.
     *
     * @return Discovery result containing signing-capable certificates and any per-token warnings,
     *         or a hard error when token discovery itself fails.
     */
    suspend fun listAvailableCertificates(): OperationResult<CertificateDiscoveryResult>
}


