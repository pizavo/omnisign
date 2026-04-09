package cz.pizavo.omnisign.data.service

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.token.*
import java.io.File
import java.security.KeyStore

/**
 * JVM implementation of [TokenService] using the EU DSS library.
 *
 * PKCS#11 token discovery is fully delegated to [Pkcs11Discoverer].  This class is responsible
 * only for loading certificates, managing credentials, and creating DSS signing tokens.
 *
 * OS-native stores (Windows MY, macOS Keychain) are added alongside PKCS#11 tokens.
 * No credential is requested during discovery; [loadCertificates] prompts via [PasswordCallback]
 * when a PIN is needed, while [loadCertificatesSilent] returns an error instead of prompting so
 * it is safe to call during passive enumeration.
 */
class DssTokenService(
	private val passwordCallback: PasswordCallback,
	private val configRepository: ConfigRepository,
	private val pkcs11Discoverer: Pkcs11Discoverer = Pkcs11Discoverer(),
) : TokenService {

	override suspend fun discoverTokens(): OperationResult<List<TokenInfo>> {
		return try {
			val config = configRepository.getCurrentConfig()
			val userLibraries = config.global.customPkcs11Libraries.map { it.name to it.path }
			val tokens = pkcs11Discoverer.discoverTokens(
				appDataPkcs11Dir = appDataPkcs11DropDir(),
				userPkcs11Libraries = userLibraries,
			).toMutableList()

			val os = System.getProperty("os.name").lowercase()
			if (os.contains("win")) {
				tokens.add(
					TokenInfo(
						id = "windows-my",
						name = "Windows Certificate Store (MY)",
						type = TokenType.WINDOWS_MY,
						requiresPin = false,
					)
				)
			}
			if (os.contains("mac")) {
				tokens.add(
					TokenInfo(
						id = "macos-keychain",
						name = "macOS Keychain",
						type = TokenType.MACOS_KEYCHAIN,
						requiresPin = false,
					)
				)
			}

			tokens.right()
		} catch (e: Exception) {
			SigningError.TokenAccessError(
				message = "Failed to discover tokens",
				details = e.message,
				cause = e,
			).left()
		}
	}

	override suspend fun requestPin(tokenInfo: TokenInfo): String? =
		passwordCallback.requestPassword(
			"Enter PIN for ${tokenInfo.name}",
			"PKCS#11 PIN Required",
		)

	/**
	 * Check physical token presence without supplying a PIN.
	 *
	 * PKCS#11 tokens are probed via `C_GetSlotList` with `CK_TRUE`, which queries the
	 * middleware for slots that currently have a card inserted.  This never calls `C_Login`
	 * and therefore never risks incrementing a wrong-PIN counter.
	 * FILE tokens are checked via [File.exists].
	 * OS-native stores always return true — the subsequent load call handles any failure.
	 */
	override suspend fun probeTokenPresent(tokenInfo: TokenInfo): Boolean = when (tokenInfo.type) {
		TokenType.PKCS11 -> tokenInfo.path?.let { probeTokenIdentities(it).isNotEmpty() } ?: false
		TokenType.FILE -> tokenInfo.path?.let { File(it).exists() } ?: false
		TokenType.WINDOWS_MY, TokenType.MACOS_KEYCHAIN -> true
	}

	/**
	 * Load certificates from [tokenInfo], prompting for credentials via [PasswordCallback]
	 * when the token requires a PIN and none is supplied.
	 */
	override suspend fun loadCertificates(
		tokenInfo: TokenInfo,
		password: String?,
	): OperationResult<List<CertificateEntry>> {
		val resolvedPassword = if (tokenInfo.requiresPin && password == null) {
			requestPin(tokenInfo) ?: return SigningError.TokenAccessError(
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
		password: String?,
	): OperationResult<List<CertificateEntry>> = loadCertificatesInternal(tokenInfo, password)

	override suspend fun getSigningToken(
		certificateEntry: CertificateEntry,
		password: String,
	): OperationResult<SigningToken> {
		return try {
			DssSigningToken(createDssToken(certificateEntry.tokenInfo, password)).right()
		} catch (e: Exception) {
			SigningError.TokenAccessError(
				message = "Failed to create signing token",
				details = e.message,
				cause = e,
			).left()
		}
	}

	override suspend fun loadCertificatesFromFile(
		filePath: String,
		password: String,
	): OperationResult<List<CertificateEntry>> {
		val file = File(filePath)
		if (!file.exists()) {
			return SigningError.TokenAccessError(
				message = "File not found: $filePath",
			).left()
		}
		val tokenInfo = TokenInfo(
			id = "file-${file.nameWithoutExtension}-${file.absolutePath.hashCode().toUInt()}",
			name = file.name,
			type = TokenType.FILE,
			path = file.absolutePath,
			requiresPin = true,
		)
		return loadCertificatesInternal(tokenInfo, password)
	}

	override suspend fun requestPassword(prompt: String, title: String): String? =
		passwordCallback.requestPassword(prompt, title)

	private fun loadCertificatesInternal(
		tokenInfo: TokenInfo,
		password: String?,
	): OperationResult<List<CertificateEntry>> {
		return try {
			val token = createDssToken(tokenInfo, password)
			val certificates = token.keys.map { key ->
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
				validFrom = certToken.notBefore.toKotlinInstant(),
				validTo = certToken.notAfter.toKotlinInstant(),
				keyUsages = extractKeyUsages(certToken.keyUsage),
				tokenInfo = tokenInfo,
			)
			}
			token.close()
			certificates.right()
		} catch (e: Exception) {
			SigningError.TokenAccessError(
				message = "Failed to load certificates from token '${tokenInfo.name}'",
				details = e.message,
				cause = e,
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
	 * Return the platform-appropriate PKCS#11 drop directory: `<appDataDir>/omnisign/pkcs11/`.
	 *
	 * Files placed here are discovered automatically by [Pkcs11Discoverer] without any config
	 * change.  The directory does not need to exist.
	 */
	private fun appDataPkcs11DropDir(): File {
		val os = System.getProperty("os.name").lowercase()
		val userHome = System.getProperty("user.home")
		val base = when {
			os.contains("win") -> System.getenv("APPDATA")?.let { File(it, "omnisign") }
				?: File(userHome, "AppData/Roaming/omnisign")
			os.contains("mac") -> File(userHome, "Library/Application Support/omnisign")
			else -> File(userHome, ".config/omnisign")
		}
		return File(base, "pkcs11")
	}

	private fun createDssToken(
		tokenInfo: TokenInfo,
		password: String?,
	): AbstractSignatureTokenConnection = when (tokenInfo.type) {
		TokenType.PKCS11 -> {
			val pin = password ?: error("PIN required for PKCS#11 token '${tokenInfo.name}'")
			Pkcs11SignatureToken(tokenInfo.path, KeyStore.PasswordProtection(pin.toCharArray()))
		}
		TokenType.FILE -> {
			val filePath = tokenInfo.path
				?: throw IllegalArgumentException("Path required for file token '${tokenInfo.name}'")
			val pwd = password ?: error("Password required for file token '${tokenInfo.name}'")
			Pkcs12SignatureToken(File(filePath), KeyStore.PasswordProtection(pwd.toCharArray()))
		}
		TokenType.WINDOWS_MY -> MSCAPISignatureToken()
		TokenType.MACOS_KEYCHAIN -> AppleSignatureToken()
	}

	private companion object {
		const val ALIAS_SERIAL_SUFFIX_LENGTH = 8

		val KEY_USAGE_NAMES = listOf(
			"digitalSignature", "nonRepudiation", "keyEncipherment", "dataEncipherment",
			"keyAgreement", "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly",
		)
	}
}

/**
 * [SigningToken] adapter wrapping a DSS [AbstractSignatureTokenConnection].
 */
private class DssSigningToken(
	private val token: AbstractSignatureTokenConnection,
) : SigningToken {
	override fun getDssToken(): Any = token
	override fun close() = token.close()
}
