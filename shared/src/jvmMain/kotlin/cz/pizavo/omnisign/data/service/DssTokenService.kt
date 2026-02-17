package cz.pizavo.omnisign.data.service

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.*
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.token.*
import java.io.File
import java.security.KeyStore

/**
 * JVM implementation of TokenService using DSS library.
 */
class DssTokenService(
    private val passwordCallback: PasswordCallback
) : TokenService {
    
    override suspend fun discoverTokens(): OperationResult<List<TokenInfo>> {
        val tokens = mutableListOf<TokenInfo>()
        
        try {
            // Discover PKCS11 tokens
            val pkcs11Tokens = discoverPkcs11Tokens()
            tokens.addAll(pkcs11Tokens)
            
            // Discover Windows MY store (Windows only)
            if (System.getProperty("os.name").lowercase().contains("win")) {
                tokens.add(
                    TokenInfo(
                        id = "windows-my",
                        name = "Windows Certificate Store (MY)",
                        type = TokenType.WINDOWS_MY,
                        requiresPin = false
                    )
                )
            }
            
            // Discover macOS Keychain (macOS only)
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                tokens.add(
                    TokenInfo(
                        id = "macos-keychain",
                        name = "macOS Keychain",
                        type = TokenType.MACOS_KEYCHAIN,
                        requiresPin = false
                    )
                )
            }
            
            return tokens.right()
        } catch (e: Exception) {
            return SigningError.TokenAccessError(
                message = "Failed to discover tokens",
                details = e.message,
                cause = e
            ).left()
        }
    }
    
    override suspend fun loadCertificates(
        tokenInfo: TokenInfo,
        password: String?
    ): OperationResult<List<CertificateEntry>> {
        return try {
            val token = createDssToken(tokenInfo, password)
            val keys = token.keys
            
            val certificates = keys.map { key ->
                val certificate = key.certificate
                val certToken = certificate.certificate
                
                // Try to get alias from key, or generate from certificate info
                val alias = runCatching {
                    key::class.java.getDeclaredField("alias").apply { isAccessible = true }.get(key) as? String
                }.getOrNull() ?: run {
                    val cn = certToken.subjectX500Principal.name
                        .split(",")
                        .find { it.trim().startsWith("CN=") }
                        ?.substringAfter("CN=")
                        ?.trim()
                        ?: "certificate"
                    "$cn-${certToken.serialNumber.toString(16).take(8)}"
                }
                
                CertificateEntry(
                    alias = alias,
                    subjectDN = certToken.subjectX500Principal.toString(),
                    issuerDN = certToken.issuerX500Principal.toString(),
                    serialNumber = certToken.serialNumber.toString(),
                    validFrom = certToken.notBefore.toString(),
                    validTo = certToken.notAfter.toString(),
                    keyUsages = emptyList(), // TODO: Extract key usages
                    tokenInfo = tokenInfo
                )
            }
            
            token.close()
            certificates.right()
        } catch (e: Exception) {
            SigningError.TokenAccessError(
                message = "Failed to load certificates from token",
                details = e.message,
                cause = e
            ).left()
        }
    }
    
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
    
    private fun discoverPkcs11Tokens(): List<TokenInfo> {
        val tokens = mutableListOf<TokenInfo>()
        
        // Common PKCS11 library locations by platform
        val os = System.getProperty("os.name").lowercase()
        val pkcs11Paths = when {
            os.contains("win") -> listOf(
                "C:\\Windows\\System32\\opensc-pkcs11.dll",
                "C:\\Program Files\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll"
            )
            os.contains("mac") -> listOf(
                "/usr/local/lib/opensc-pkcs11.so",
                "/Library/OpenSC/lib/opensc-pkcs11.so"
            )
            else -> listOf(
                "/usr/lib/opensc-pkcs11.so",
                "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so",
                "/usr/local/lib/opensc-pkcs11.so"
            )
        }
        
        pkcs11Paths.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                tokens.add(
                    TokenInfo(
                        id = "pkcs11-$path",
                        name = "PKCS#11 Token (${file.name})",
                        type = TokenType.PKCS11,
                        path = path,
                        requiresPin = true
                    )
                )
            }
        }
        
        return tokens
    }
    
    private fun createDssToken(tokenInfo: TokenInfo, password: String?): AbstractSignatureTokenConnection {
        return when (tokenInfo.type) {
            TokenType.PKCS11 -> {
                val pin = password ?: passwordCallback.requestPassword(
                    "Enter PIN for ${tokenInfo.name}",
                    "PKCS#11 PIN Required"
                ) ?: throw IllegalStateException("PIN required for PKCS#11 token")
                
                Pkcs11SignatureToken(tokenInfo.path, KeyStore.PasswordProtection(pin.toCharArray()))
            }
            
            TokenType.FILE -> {
                // For PKCS12/JKS files
                val filePath = tokenInfo.path ?: throw IllegalArgumentException("Path required for file token")
                val pwd = password ?: passwordCallback.requestPassword(
                    "Enter password for ${tokenInfo.name}",
                    "Keystore Password Required"
                ) ?: throw IllegalStateException("Password required for keystore")
                
                Pkcs12SignatureToken(File(filePath), KeyStore.PasswordProtection(pwd.toCharArray()))
            }
            
            TokenType.WINDOWS_MY -> {
                MSCAPISignatureToken()
            }
            
            TokenType.MACOS_KEYCHAIN -> {
                // DSS doesn't have direct macOS keychain support, this would need custom implementation
                // For now, throw unsupported exception
                throw UnsupportedOperationException("macOS Keychain support not yet implemented")
            }
        }
    }
}

/**
 * Implementation of SigningToken wrapping DSS token.
 */
private class DssSigningToken(
    private val token: AbstractSignatureTokenConnection
) : SigningToken {
    override fun getDssToken(): Any = token
    override fun close() = token.close()
}










