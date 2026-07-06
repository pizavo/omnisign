package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.value.Sensitive

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
     * Tokens that require a PIN and have no stored credential are handled in two phases:
     *
     * 1. **Silent pass (always)** — every present token attempts a load using its stored credential
     *    or no PIN.  Tokens with no stored credential and a PIN requirement are collected as locked.
     * 2. **Prompted pass (when [promptForLocked] is `true`)** — locked tokens are revisited
     *    sequentially and the configured [cz.pizavo.omnisign.platform.PasswordCallback] is asked for
     *    a PIN.  Tokens whose prompt is satisfied yield certificates; tokens whose prompt is
     *    cancelled (callback returns `null`) remain in [CertificateDiscoveryResult.lockedTokens].
     *
     * @param promptForLocked When `true` (default), invoke the platform [PasswordCallback] for any
     *   token that survives the silent pass.  Set to `false` for non-interactive contexts (server,
     *   scripted CLI) — locked tokens then remain in `lockedTokens` without any prompt.
     * @return Discovery result containing signing-capable certificates and any per-token warnings,
     *         or a hard error when token discovery itself fails.
     */
    suspend fun listAvailableCertificates(
        promptForLocked: Boolean = true,
    ): OperationResult<CertificateDiscoveryResult>

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

    /**
     * Enumerate the certificates held in a **pre-configured** PKCS#12 keystore, non-interactively.
     *
     * Unlike [loadCertificatesFromFile], the password is supplied by the caller ([keystorePassword])
     * rather than prompted for, so this is safe in headless contexts (the server signing from a file
     * keystore configured via `operations.signingKeystorePath`). The returned
     * [AvailableCertificateInfo.alias] values are derived identically to the ones
     * [signDocument] resolves when signing from the same keystore, so a certificate listed here can
     * be selected and handed straight back to [signDocument] as
     * [SigningParameters.certificateAlias] with a guaranteed match.
     *
     * Token discovery ([listAvailableCertificates]) deliberately does **not** surface this keystore —
     * it only enumerates PKCS#11 and OS-store tokens — so callers that expose a server's file-keystore
     * identity (e.g. the certificate-discovery route) merge this result in explicitly.
     *
     * @param keystoreFile Absolute path to the PKCS#12 (.p12 / .pfx) keystore.
     * @param keystorePassword Keystore password, or `null` to attempt an empty password.
     * @return Certificates found in the keystore, or an error when the file is missing or cannot be
     *   opened. Web / remote implementations that hold no local keystore return an error.
     */
    suspend fun listCertificatesFromKeystore(
        keystoreFile: String,
        keystorePassword: Sensitive<String>?,
    ): OperationResult<List<AvailableCertificateInfo>>
}


