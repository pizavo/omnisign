package cz.pizavo.omnisign.data.service

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.MSCAPISignatureToken
import eu.europa.esig.dss.token.Pkcs11SignatureToken
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import java.io.File
import java.security.KeyStore

/**
 * JVM implementation of [TokenService] using the EU DSS library.
 *
 * Token discovery probes well-known PKCS#11 middleware library locations on disk as well as
 * OS-native stores (Windows MY, macOS Keychain).  No credential is requested during discovery;
 * the [loadCertificates] variant prompts via [PasswordCallback] when a PIN is needed, while
 * [loadCertificatesSilent] returns an error instead of prompting so it is safe to call during
 * passive enumeration.
 */
class DssTokenService(
    private val passwordCallback: PasswordCallback
) : TokenService {

    override suspend fun discoverTokens(): OperationResult<List<TokenInfo>> {
        return try {
            val tokens = mutableListOf<TokenInfo>()
            tokens.addAll(discoverPkcs11Tokens())

            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                tokens.add(
                    TokenInfo(
                        id = "windows-my",
                        name = "Windows Certificate Store (MY)",
                        type = TokenType.WINDOWS_MY,
                        requiresPin = false
                    )
                )
            }
            if (os.contains("mac")) {
                tokens.add(
                    TokenInfo(
                        id = "macos-keychain",
                        name = "macOS Keychain",
                        type = TokenType.MACOS_KEYCHAIN,
                        requiresPin = false
                    )
                )
            }

            tokens.right()
        } catch (e: Exception) {
            SigningError.TokenAccessError(
                message = "Failed to discover tokens",
                details = e.message,
                cause = e
            ).left()
        }
    }

    /**
     * Load certificates from [tokenInfo], prompting for credentials via [PasswordCallback]
     * when the token requires a PIN and none is supplied.
     */
    override suspend fun loadCertificates(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>> {
        val resolvedPassword = if (tokenInfo.requiresPin && password == null) {
            passwordCallback.requestPassword(
                "Enter PIN for ${tokenInfo.name}",
                "PKCS#11 PIN Required"
            ) ?: return SigningError.TokenAccessError(
                message = "PIN entry cancelled for '${tokenInfo.name}'"
            ).left()
        } else {
            password
        }
        return loadCertificatesInternal(tokenInfo, resolvedPassword)
    }

    /**
     * Load certificates without prompting for credentials.
     * Returns an error immediately when the token requires a PIN and none is supplied.
     * Prefer this during passive discovery to avoid blocking on user input.
     */
    override suspend fun loadCertificatesSilent(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>> = loadCertificatesInternal(tokenInfo, password)

    override suspend fun getSigningToken(
        certificateEntry: CertificateEntry,
        password: String
    ): OperationResult<SigningToken> {
        return try {
            val token = createDssToken(certificateEntry.tokenInfo, password)
            DssSigningToken(token).right()
        } catch (e: Exception) {
            SigningError.TokenAccessError(
                message = "Failed to create signing token",
                details = e.message,
                cause = e
            ).left()
        }
    }

    private fun loadCertificatesInternal(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>> {
        return try {
            val token = createDssToken(tokenInfo, password)
            val keys = token.keys

            val certificates = keys.map { key ->
                val certToken = key.certificate.certificate

                val alias = runCatching {
                    key::class.java.getDeclaredField("alias")
                        .apply { isAccessible = true }
                        .get(key) as? String
                }.getOrNull() ?: run {
                    val cn = certToken.subjectX500Principal.name
                        .split(",")
                        .find { it.trim().startsWith("CN=") }
                        ?.substringAfter("CN=")
                        ?.trim()
                        ?: "certificate"
                    "$cn-${certToken.serialNumber.toString(16).take(ALIAS_SERIAL_SUFFIX_LENGTH)}"
                }

                CertificateEntry(
                    alias = alias,
                    subjectDN = certToken.subjectX500Principal.toString(),
                    issuerDN = certToken.issuerX500Principal.toString(),
                    serialNumber = certToken.serialNumber.toString(),
                    validFrom = certToken.notBefore.toString(),
                    validTo = certToken.notAfter.toString(),
                    keyUsages = extractKeyUsages(certToken.keyUsage),
                    tokenInfo = tokenInfo
                )
            }

            token.close()
            certificates.right()
        } catch (e: Exception) {
            SigningError.TokenAccessError(
                message = "Failed to load certificates from token '${tokenInfo.name}'",
                details = e.message,
                cause = e
            ).left()
        }
    }

    /**
     * Convert the X.509 key usage bitmask returned by
     * [java.security.cert.X509Certificate.getKeyUsage] into a list of human-readable names.
     * Returns an empty list when the extension is absent (null).
     */
    private fun extractKeyUsages(keyUsage: BooleanArray?): List<String> {
        if (keyUsage == null) return emptyList()
        return KEY_USAGE_NAMES.filterIndexed { index, _ -> index < keyUsage.size && keyUsage[index] }
    }

    /**
     * Probe well-known PKCS#11 middleware library locations for the current OS and return a
     * [TokenInfo] for every library file that is actually present on disk.
     *
     * Covered middleware (non-exhaustive):
     * - SafeNet eToken (5110, Fusion CC, …) — `eTPKCS11.dll` / `libeTPkcs11.so`
     * - Thales / Gemalto IDPrime — `gclib.dll` / `libgclib.so`
     * - OpenSC — `opensc-pkcs11.dll` / `opensc-pkcs11.so`
     * - SecMaker Net iD — `iidp11.dll`
     * - Charismathics — `cmP11.dll`
     * - SoftHSM2 — `softhsm2-x64.dll` / `libsofthsm2.so`
     * - macOS built-in smart-card PKCS#11 shim
     */
    private fun discoverPkcs11Tokens(): List<TokenInfo> {
        val os = System.getProperty("os.name").lowercase()
        return pkcs11CandidatesForOs(os)
            .filter { (_, path) -> File(path).exists() }
            .map { (name, path) ->
                TokenInfo(
                    id = "pkcs11-${File(path).name}",
                    name = name,
                    type = TokenType.PKCS11,
                    path = path,
                    requiresPin = true
                )
            }
    }

    /**
     * Return (display name, absolute path) pairs for PKCS#11 middleware candidates
     * appropriate for the given [os] string (lowercase).
     */
    private fun pkcs11CandidatesForOs(os: String): List<Pair<String, String>> = when {
        os.contains("win") -> listOf(
            "SafeNet eToken" to "C:\\Windows\\System32\\eTPKCS11.dll",
            "SafeNet eToken (SysWOW64)" to "C:\\Windows\\SysWOW64\\eTPKCS11.dll",
            "SafeNet Authentication Client" to
                "C:\\Program Files\\SafeNet\\Authentication\\SAC\\x64\\eTPKCS11.dll",
            "SafeNet Authentication Client (x86)" to
                "C:\\Program Files (x86)\\SafeNet\\Authentication\\SAC\\x32\\eTPKCS11.dll",
            "Thales/Gemalto IDPrime" to "C:\\Windows\\System32\\gclib.dll",
            "Thales/Gemalto IDPrime (SysWOW64)" to "C:\\Windows\\SysWOW64\\gclib.dll",
            "OpenSC (System32)" to "C:\\Windows\\System32\\opensc-pkcs11.dll",
            "OpenSC" to "C:\\Program Files\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll",
            "OpenSC (x86)" to
                "C:\\Program Files (x86)\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll",
            "SecMaker Net iD" to "C:\\Windows\\System32\\iidp11.dll",
            "Charismathics PKCS#11" to "C:\\Windows\\System32\\cmP11.dll",
            "SoftHSM2" to "C:\\SoftHSM2\\lib\\softhsm2-x64.dll",
        )
        os.contains("mac") -> listOf(
            "SafeNet eToken" to "/usr/local/lib/libeTPkcs11.dylib",
            "SafeNet Authentication Client" to "/usr/local/lib/libsac.dylib",
            "Thales/Gemalto IDPrime" to "/usr/local/lib/libgclib.dylib",
            "OpenSC" to "/usr/local/lib/opensc-pkcs11.so",
            "OpenSC (Homebrew)" to "/opt/homebrew/lib/opensc-pkcs11.so",
            "OpenSC (Library)" to "/Library/OpenSC/lib/opensc-pkcs11.so",
            "macOS Smart Card PKCS#11" to "/usr/lib/libctkpcscd.dylib",
            "SoftHSM2 (Homebrew)" to "/opt/homebrew/lib/softhsm/libsofthsm2.so",
            "SoftHSM2" to "/usr/local/lib/softhsm/libsofthsm2.so",
        )
        else -> listOf(
            "SafeNet eToken" to "/usr/lib/libeTPkcs11.so",
            "SafeNet eToken (lib64)" to "/usr/lib64/libeTPkcs11.so",
            "SafeNet Authentication Client" to "/usr/lib/libsac.so",
            "Thales/Gemalto IDPrime" to "/usr/lib/libgclib.so",
            "Thales/Gemalto IDPrime (lib64)" to "/usr/lib64/libgclib.so",
            "OpenSC" to "/usr/lib/opensc-pkcs11.so",
            "OpenSC (x86_64)" to "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (aarch64)" to "/usr/lib/aarch64-linux-gnu/opensc-pkcs11.so",
            "OpenSC (local)" to "/usr/local/lib/opensc-pkcs11.so",
            "SoftHSM2" to "/usr/lib/softhsm/libsofthsm2.so",
            "SoftHSM2 (lib64)" to "/usr/lib64/softhsm/libsofthsm2.so",
        )
    }

    private fun createDssToken(
        tokenInfo: TokenInfo,
        password: String?
    ): AbstractSignatureTokenConnection = when (tokenInfo.type) {
        TokenType.PKCS11 -> {
            val pin = password
                ?: error("PIN required for PKCS#11 token '${tokenInfo.name}'")
            Pkcs11SignatureToken(tokenInfo.path, KeyStore.PasswordProtection(pin.toCharArray()))
        }
        TokenType.FILE -> {
            val filePath = tokenInfo.path
                ?: throw IllegalArgumentException("Path required for file token '${tokenInfo.name}'")
            val pwd = password
                ?: error("Password required for file token '${tokenInfo.name}'")
            Pkcs12SignatureToken(File(filePath), KeyStore.PasswordProtection(pwd.toCharArray()))
        }
        TokenType.WINDOWS_MY -> MSCAPISignatureToken()
        TokenType.MACOS_KEYCHAIN ->
            throw UnsupportedOperationException("macOS Keychain support not yet implemented")
    }

    private companion object {
        const val ALIAS_SERIAL_SUFFIX_LENGTH = 8

        val KEY_USAGE_NAMES = listOf(
            "digitalSignature",
            "nonRepudiation",
            "keyEncipherment",
            "dataEncipherment",
            "keyAgreement",
            "keyCertSign",
            "cRLSign",
            "encipherOnly",
            "decipherOnly"
        )
    }
}

/**
 * [SigningToken] adapter wrapping a DSS [AbstractSignatureTokenConnection].
 */
private class DssSigningToken(
    private val token: AbstractSignatureTokenConnection
) : SigningToken {
    override fun getDssToken(): Any = token
    override fun close() = token.close()
}
