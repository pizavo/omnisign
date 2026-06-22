package cz.pizavo.omnisign.data.service

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.value.commonNameOf
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.Pkcs11DiagnosticSnapshot
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.platform.PasswordCallback
import eu.europa.esig.dss.token.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * JVM implementation of [TokenService] using the EU DSS library.
 *
 * PKCS#11 enumeration is delegated to the discovery subsystem — the cached token list via
 * [Pkcs11Discoverer], physical-presence checks via [Pkcs11ProbeCache], and the diagnostic
 * snapshot via [Pkcs11CandidateCollector].  This class is responsible only for loading
 * certificates, managing credentials, and creating DSS signing tokens.
 *
 * OS-native stores (Windows MY, macOS Keychain) are added alongside PKCS#11 tokens.
 * No credential is requested during discovery; [loadCertificates] prompts via [PasswordCallback]
 * when a PIN is needed, while [loadCertificatesSilent] returns an error instead of prompting so
 * it is safe to call during passive enumeration.
 *
 * [discoverTokens] is a **passive cache reader** — it suspends on
 * [Pkcs11Discoverer.discoveryRunning] until any in-flight cycle (warmup, invalidator-launched
 * rediscovery) finishes, then reads [Pkcs11Discoverer.getCachedTokens] without triggering its
 * own discovery.  The producers are [Pkcs11WarmupService] at startup and
 * [Pkcs11CacheInvalidator] on PC/SC reader-state events.
 */
class DssTokenService(
	private val passwordCallback: PasswordCallback,
	private val pkcs11Discoverer: Pkcs11Discoverer = Pkcs11Discoverer(),
	private val probeCache: Pkcs11ProbeCache = Pkcs11ProbeCache(),
	private val candidateCollector: Pkcs11CandidateCollector = Pkcs11CandidateCollector(),
	private val prober: Pkcs11Prober = Pkcs11SubprocessProber(),
	private val pkcs11CacheInvalidator: Pkcs11CacheInvalidator? = null,
	private val pcscMonitorService: PcscMonitorService? = null,
	private val configRepository: ConfigRepository? = null,
) : TokenService {

	override val discoveryRunning: StateFlow<Boolean> = pkcs11Discoverer.discoveryRunning

	override suspend fun getDiagnosticSnapshot(): Pkcs11DiagnosticSnapshot {
		val readers = pcscMonitorService?.currentReaders()?.map { reader ->
			Pkcs11DiagnosticSnapshot.PcscReaderInfo(
				name = reader.name,
				cardPresent = reader.cardPresent,
				atrHex = reader.atrHex,
			)
		} ?: emptyList()
		val userLibs = configRepository?.getCurrentConfig()
			?.global?.customPkcs11Libraries
			?.map { it.name to it.path }
			?: emptyList()
		val dropDir = pkcs11DropDir()
		val candidates = candidateCollector
			.collectCandidates(appDataPkcs11Dir = dropDir, userPkcs11Libraries = userLibs)
			.map { (name, path) -> Pkcs11DiagnosticSnapshot.CandidateLibrary(name, path) }
		return Pkcs11DiagnosticSnapshot(
			pcscReaders = readers,
			candidateLibraries = candidates,
			dropDirectoryPath = dropDir.absolutePath,
		)
	}

	override fun rescanTokens() {
		val invalidator = pkcs11CacheInvalidator
		if (invalidator == null) {
			logger.warn { "rescanTokens() called but no Pkcs11CacheInvalidator wired — rescan ignored" }
			return
		}
		logger.info { "Manual rescan requested — invalidating caches and re-running discovery" }
		invalidator.rescan()
	}

	override suspend fun discoverTokens(): OperationResult<List<TokenInfo>> {
		return try {
			pkcs11Discoverer.discoveryRunning.filter { !it }.first()
			val tokens = pkcs11Discoverer.getCachedTokens().toMutableList()

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
			SigningError.discoverTokensFailed(details = e.message, cause = e).left()
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
	 * PKCS#11 tokens are checked via [Pkcs11ProbeCache.probeLibrary], which returns a cached
	 * probe result when the cache is warm (the common case after warmup or an
	 * invalidator-driven rediscovery) and only on a cache miss spawns the configured probe
	 * strategy (subprocess-based by default, with a classpath fallback for jpackage
	 * distributions).  That subprocess probe calls `C_GetSlotList` with `CK_TRUE`, which
	 * queries the middleware for slots that currently have a card inserted.  This never calls
	 * `C_Login` and therefore never risks incrementing a wrong-PIN counter.
	 * FILE tokens are checked via [File.exists].
	 * OS-native stores always return true — the subsequent load call handles any failure.
	 */
	override suspend fun probeTokenPresent(tokenInfo: TokenInfo): Boolean = when (tokenInfo.type) {
		TokenType.PKCS11 -> {
			val path = tokenInfo.path
			if (path == null) {
				logger.warn { "PKCS#11 token '${tokenInfo.name}' has no library path — treating as absent" }
				false
			} else {
				val present = probeCache.probeLibrary(path).isNotEmpty()
				logger.debug { "PKCS#11 token '${tokenInfo.name}' at '$path': present=$present" }
				present
			}
		}
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
			requestPin(tokenInfo) ?: return SigningError.pinEntryCancelled(tokenInfo.name).left()
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

	/**
	 * Enumerate a PKCS#11 token's public certificate objects without `C_Login`.
	 *
	 * Runs the out-of-process `--certs` probe ([runCertProbeSubprocess]) so a misbehaving
	 * module cannot crash this JVM, parses the returned DER, and builds [CertificateEntry]s
	 * whose [CertificateEntry.alias] is the same deterministic, content-derived value the
	 * PIN path ([loadCertificatesInternal]) produces, and whose [CertificateEntry.pkcs11SlotId]
	 * records the exact slot each object was enumerated in — so a certificate listed here
	 * resolves to the same key, in the same slot, when [DssSigningRepository] later signs with
	 * the PIN, even on a card that presents more than one token-present slot.
	 *
	 * Returns an empty list (never an error) for non-PKCS#11 tokens, when the probe fails,
	 * or when the token exposes no public certificates — callers then fall back to the
	 * PIN-prompt path.
	 */
	override suspend fun listCertificatesNoLogin(
		tokenInfo: TokenInfo,
	): OperationResult<List<CertificateEntry>> {
		if (tokenInfo.type != TokenType.PKCS11) return emptyList<CertificateEntry>().right()
		val libraryPath = tokenInfo.path ?: return emptyList<CertificateEntry>().right()
		val certs = runCatching {
			val result = prober.runCertProbe(libraryPath, Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS)
			if (result is Pkcs11SubprocessResult.Success) {
				parseProbeNoLoginCerts(result.stdout).map { it.toCertificateEntry(tokenInfo) }
			} else {
				emptyList()
			}
		}.getOrDefault(emptyList())
		return certs.right()
	}

	override suspend fun getSigningToken(
		certificateEntry: CertificateEntry,
		password: String,
	): OperationResult<SigningToken> {
		return try {
			DssSigningToken(createDssToken(certificateEntry.tokenInfo, password)).right()
		} catch (e: Exception) {
			SigningError.createSigningTokenFailed(details = e.message, cause = e).left()
		}
	}

	override suspend fun loadCertificatesFromFile(
		filePath: String,
		password: String,
	): OperationResult<List<CertificateEntry>> {
		val file = File(filePath)
		if (!file.exists()) {
			return SigningError.fileNotFound(filePath).left()
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
				val (isQualified, isQscd) = extractQcStatements(certToken)
				CertificateEntry(
					alias = pkcs11CertAlias(certToken, tokenInfo),
					subjectDN = certToken.subjectX500Principal.toString(),
					issuerDN = certToken.issuerX500Principal.toString(),
					serialNumber = certToken.serialNumber.toString(),
					validFrom = certToken.notBefore.toKotlinInstant(),
					validTo = certToken.notAfter.toKotlinInstant(),
					keyUsages = extractKeyUsages(certToken.keyUsage),
					tokenInfo = tokenInfo,
					isQualified = isQualified,
					isQscd = isQscd,
					pkcs11SlotId = tokenInfo.pkcs11SlotId,
				)
			}
			token.close()
			certificates.right()
		} catch (e: Exception) {
			SigningError.loadCertificatesFromTokenFailed(tokenInfo.name, details = e.message, cause = e).left()
		}
	}

	/**
	 * Deterministic certificate alias: `<CN>-<serialHex>@<tokenInfo.id>`.
	 *
	 * The leading `<CN>-<serialHex>` is derived purely from the certificate; the trailing
	 * `@<tokenInfo.id>` records *which source* the certificate was read from.  [TokenInfo.id]
	 * is stable for a given source — `pkcs11-<tokenSerial>` for a hardware token (the physical
	 * token serial, never the transient slot), `windows-my` / `macos-keychain` for the OS
	 * stores, `file-…` for an imported keystore — and is reproduced identically by both the
	 * no-login probe and the logged-in keystore for the same token, because both build their
	 * [CertificateEntry] from the same discovered [TokenInfo].
	 *
	 * This keeps two invariants at once:
	 * - the alias the user selects from a no-PIN listing is the exact alias the logged-in
	 *   keystore yields for the same physical certificate at signing time, so
	 *   [DssSigningRepository]'s key resolution stays unchanged; and
	 * - the same certificate present on two different sources (e.g. a hardware token and the
	 *   Windows store that mirrors it) yields two distinct aliases, so selection and
	 *   sign-time resolution can no longer collapse them onto the wrong key.
	 */
	private fun pkcs11CertAlias(cert: X509Certificate, tokenInfo: TokenInfo): String {
		val cn = commonNameOf(cert.subjectX500Principal.name) ?: "certificate"
		return "$cn-${cert.serialNumber.toString(RADIX_HEX)}@${tokenInfo.id}"
	}

	/**
	 * Build a [CertificateEntry] from a no-login-parsed certificate, mirroring the field
	 * derivation of [loadCertificatesInternal] (same alias, DN formatting, validity, key
	 * usages, and QC-statement extraction) so the two paths are interchangeable.
	 *
	 * [CertificateEntry.pkcs11SlotId] is taken from the probe's per-certificate
	 * [Pkcs11NoLoginParsedCert.slotId] — the exact slot the object was enumerated in — so a
	 * card that exposes several token-present slots resolves each certificate to the slot
	 * that holds its key, even though discovery collapses the card to a single [TokenInfo].
	 */
	private fun Pkcs11NoLoginParsedCert.toCertificateEntry(tokenInfo: TokenInfo): CertificateEntry {
		val (isQualified, isQscd) = extractQcStatements(certificate)
		return CertificateEntry(
			alias = pkcs11CertAlias(certificate, tokenInfo),
			subjectDN = certificate.subjectX500Principal.toString(),
			issuerDN = certificate.issuerX500Principal.toString(),
			serialNumber = certificate.serialNumber.toString(),
			validFrom = certificate.notBefore.toKotlinInstant(),
			validTo = certificate.notAfter.toKotlinInstant(),
			keyUsages = extractKeyUsages(certificate.keyUsage),
			tokenInfo = tokenInfo,
			isQualified = isQualified,
			isQscd = isQscd,
			pkcs11SlotId = slotId,
		)
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
	 * Read the QCStatements X.509 extension from [cert] and return whether the certificate
	 * carries the QcCompliance and QcSSCD statements.
	 *
	 * Both values are `null` when the QCStatements extension (`1.3.6.1.5.5.7.1.3`) is absent
	 * or cannot be parsed — callers should treat `null` as "unknown" rather than "false".
	 *
	 * @return Pair of (isQualified, isQscd); each element is `true` when the corresponding
	 *   OID is present in the statement sequence, `false` when the extension exists but the
	 *   OID is absent, and `null` when the extension itself is missing or unreadable.
	 */
	private fun extractQcStatements(cert: X509Certificate): Pair<Boolean?, Boolean?> {
		val extBytes = cert.getExtensionValue(QC_STATEMENTS_EXTENSION_OID) ?: return null to null
		return runCatching {
			val octetStr = ASN1Primitive.fromByteArray(extBytes) as DEROctetString
			val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(octetStr.octets))
			val oids = (0 until seq.size()).mapNotNull { i ->
				(seq.getObjectAt(i) as? ASN1Sequence)
					?.getObjectAt(0)
					?.let { ASN1ObjectIdentifier.getInstance(it).id }
			}.toSet()
			(QC_COMPLIANCE_OID in oids) to (QC_SSCD_OID in oids)
		}.getOrDefault(null to null)
	}

	private fun createDssToken(
		tokenInfo: TokenInfo,
		password: String?,
	): AbstractSignatureTokenConnection = when (tokenInfo.type) {
		TokenType.PKCS11 -> {
			val pin = password ?: error("PIN required for PKCS#11 token '${tokenInfo.name}'")
			val protection = KeyStore.PasswordProtection(pin.toCharArray())
			val slotId = tokenInfo.pkcs11SlotId
			if (slotId != null) {
				Pkcs11SignatureToken(
					tokenInfo.path,
					PrefilledPasswordCallback(protection),
					slotId.toInt(),
					-1,
					null,
				)
			} else {
				Pkcs11SignatureToken(tokenInfo.path, protection)
			}
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
		val logger = KotlinLogging.logger {}

		/**
		 * Radix for rendering a certificate serial number in the deterministic alias.
		 */
		const val RADIX_HEX = 16

		/**
		 * OID of the QCStatements X.509 extension (RFC 3739 / ETSI EN 319 412).
		 */
		const val QC_STATEMENTS_EXTENSION_OID = "1.3.6.1.5.5.7.1.3"

		/**
		 * OID for `id-etsi-qcs-QcCompliance` — indicates a qualified certificate under eIDAS.
		 */
		const val QC_COMPLIANCE_OID = "0.4.0.1862.1.1"

		/**
		 * OID for `id-etsi-qcs-QcSSCD` — indicates the private key resides on a QSCD.
		 */
		const val QC_SSCD_OID = "0.4.0.1862.1.4"

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
