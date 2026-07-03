package cz.pizavo.omnisign.domain.service

import arrow.core.right
import cz.pizavo.omnisign.domain.model.result.OperationResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Service for discovering and accessing cryptographic tokens and certificates.
 */
interface TokenService {
    /**
     * `true` while *any* token-discovery cycle is in flight — startup warmup, dialog-driven
     * discovery, or background rediscovery triggered by a PC/SC reader-state event.
     *
     * Consumers that should react to discovery completing (e.g. the sign-dialog ViewModel
     * refreshing its certificate list when a card insertion finishes populating the cache)
     * collect this flow and act on each transition to `false`.  Initial value is `false` on
     * implementations that do not perform discovery (server, web) — those return a
     * never-changing constant flow.
     *
     * The signal is purely informational; calling [discoverTokens] does not require waiting
     * on it (the JVM implementation already suspends internally until any in-flight cycle
     * settles before reading the cache).
     */
    val discoveryRunning: StateFlow<Boolean>

    /**
     * Return a read-only snapshot of what the PKCS#11 discovery layer currently sees on the
     * local machine: visible PC/SC readers, the candidate library paths discovery would probe,
     * and the platform drop-directory path.
     *
     * Used by the sign-dialog "Show diagnostic info" affordance so users can self-serve when
     * their token isn't being picked up — see which readers PC/SC reports, learn where to
     * drop a library file, or confirm that a custom library path is recognised.
     *
     * Default implementation returns [Pkcs11DiagnosticSnapshot.EMPTY] for non-JVM platforms
     * that perform no local discovery.
     */
    suspend fun getDiagnosticSnapshot(): Pkcs11DiagnosticSnapshot = Pkcs11DiagnosticSnapshot.EMPTY

    /**
     * Trigger a manual rescan of all discoverable tokens.
     *
     * Clears every cached probe and candidate result, then re-runs the full discovery
     * cycle in the background.  Intended for the "Rescan tokens" UI affordance covering
     * the edge case where the user installs new PKCS#11 middleware *while the app is
     * running*: PC/SC events fire only for hardware state changes, so filesystem-level
     * installs would otherwise stay invisible until app restart.
     *
     * Fire-and-forget: returns immediately.  Progress and completion are observable via
     * [discoveryRunning] — UI consumers that already react to that flow get the loader
     * indicator and the auto-refresh of the certificate list for free.
     *
     * Default implementation is a no-op for [TokenService]s that perform no local
     * discovery (web target, future remote-only impls).
     */
    fun rescanTokens() {
    }

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
     * List a token's certificate objects **without** authenticating (`C_Login` is never
     * called, no PIN is requested).
     *
     * Returns only certificates that are public objects on the token — the basis for
     * showing a certificate in the signing dialog before the user enters a PIN, deferring
     * authentication to the actual signing operation.  An empty list means the token
     * exposes no public certificate objects (they are private), in which case callers
     * should fall back to the PIN-prompt path.
     *
     * Default implementation returns an empty list: platforms with no local PKCS#11 stack
     * (server, web) have nothing to enumerate this way.
     *
     * @param tokenInfo Token to enumerate; only PKCS#11 tokens yield results.
     * @return Public certificates readable without a PIN, or an empty list.
     */
    suspend fun listCertificatesNoLogin(
        tokenInfo: TokenInfo,
    ): OperationResult<List<CertificateEntry>> = emptyList<CertificateEntry>().right()
    
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

    /**
     * Open a signing token for [tokenInfo], opening exactly the slot it is pinned to.
     *
     * Unlike [getSigningToken], this needs no [CertificateEntry] — the caller selects the
     * signing key from the returned token's own key enumeration.  This lets the sign path
     * open the token and authenticate **once**, then both pick the certificate and sign from
     * that single enumeration, instead of opening (and, on a token with its own secure PIN
     * pad, re-prompting) the token a second time just to read the certificate list first.
     *
     * @param tokenInfo Token (and slot) to open.
     * @param password Password/PIN for the token.
     * @return Token connection that can be used for signing, or an error.
     */
    suspend fun openSigningToken(
        tokenInfo: TokenInfo,
        password: String
    ): OperationResult<SigningToken>

    /**
     * Load certificates from a PKCS#12 file on demand.
     *
     * Creates a transient [TokenInfo] with [cz.pizavo.omnisign.domain.model.config.enums.TokenType.FILE]
     * and attempts to open the keystore with the given password.
     *
     * @param filePath Absolute path to the PKCS#12 (.p12 / .pfx) file.
     * @param password Password for the PKCS#12 keystore.
     * @return List of certificates or error.
     */
    suspend fun loadCertificatesFromFile(
        filePath: String,
        password: String,
    ): OperationResult<List<CertificateEntry>>

    /**
     * Prompt the user for a password via the platform [cz.pizavo.omnisign.platform.PasswordCallback].
     *
     * @param prompt Message to display.
     * @param title Dialog title.
     * @return Entered password, or null if cancelled.
     */
    suspend fun requestPassword(prompt: String, title: String): String?
}
