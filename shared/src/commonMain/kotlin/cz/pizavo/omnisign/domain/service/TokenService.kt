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
     * Load certificates without prompting for credentials.
     * Returns an error immediately when the token requires a PIN and none is supplied.
     * Use this during passive discovery to avoid blocking on user input.
     *
     * @param tokenInfo Token to load certificates from
     * @param password Password/PIN for the token or null to skip PIN-protected tokens
     * @return List of certificates or error
     */
    suspend fun loadCertificatesSilent(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>>
    
    /**
     * Check whether a token is physically accessible without supplying a PIN.
     *
     * For PKCS#11 tokens this probes the middleware library for slots that currently
     * hold a token (CK_TRUE flag), which is safe because it never calls [C_Login] and
     * therefore never risks incrementing a wrong-PIN counter.
     * For file-based tokens it checks whether the file exists.
     * For OS-native stores (Windows MY, macOS Keychain) it always returns true and
     * lets the subsequent load decide.
     *
     * @param tokenInfo Token to probe.
     * @return true when the token appears to be connected/accessible.
     */
    suspend fun probeTokenPresent(tokenInfo: TokenInfo): Boolean

    /**
     * Prompt the user for a PIN for the given token.
     *
     * Delegates to the platform's [cz.pizavo.omnisign.platform.PasswordCallback].
     * Returns null when the user cancels the prompt or no callback is available.
     *
     * @param tokenInfo Token that requires a PIN
     * @return Entered PIN string, or null if cancelled
     */
    suspend fun requestPin(tokenInfo: TokenInfo): String?

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
