package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
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
     * Tokens that require a PIN are reported in [CertificateDiscoveryResult.lockedTokens]
     * rather than as warnings so the UI can offer an unlock action.
     *
     * @return Discovery result containing signing-capable certificates and any per-token warnings,
     *         or a hard error when token discovery itself fails.
     */
    suspend fun listAvailableCertificates(): OperationResult<CertificateDiscoveryResult>

    /**
     * Unlock a PIN-protected token by prompting the user for credentials.
     *
     * Called when the user clicks "Unlock" on a locked token in the signing dialog.
     * Prompts via [cz.pizavo.omnisign.platform.PasswordCallback] and loads certificates.
     *
     * @param tokenId Stable identifier of the token to unlock (from [LockedTokenInfo.tokenId]).
     * @return List of [AvailableCertificateInfo] from the unlocked token, or an error.
     */
    suspend fun unlockToken(tokenId: String): OperationResult<List<AvailableCertificateInfo>>

    /**
     * Load certificates from a PKCS#12 file selected by the user.
     *
     * Prompts for the file password, opens the keystore, and returns available certificates.
     * The file is treated as a transient token for this session only.
     *
     * @param filePath Absolute path to the PKCS#12 (.p12 / .pfx) file.
     * @return List of [AvailableCertificateInfo] from the file, or an error.
     */
    suspend fun loadCertificatesFromFile(filePath: String): OperationResult<List<AvailableCertificateInfo>>
}


