package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Service for discovering and accessing cryptographic tokens and certificates.
 */
interface TokenService {
    /**
     * Discover available tokens on the system.
     *
     * @return List of discovered tokens or error
     */
    suspend fun discoverTokens(): OperationResult<List<TokenInfo>>
    
    /**
     * Load certificates from a specific token.
     *
     * @param tokenInfo Token to load certificates from
     * @param password Password/PIN for the token (if required)
     * @return List of certificates or error
     */
    suspend fun loadCertificates(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>>
    
    /**
     * Get a signing token for the specified certificate.
     *
     * @param certificateEntry Certificate to create token for
     * @param password Password/PIN for the token
     * @return Token connection that can be used for signing
     */
    suspend fun getSigningToken(
        certificateEntry: CertificateEntry,
        password: String
    ): OperationResult<SigningToken>
}


