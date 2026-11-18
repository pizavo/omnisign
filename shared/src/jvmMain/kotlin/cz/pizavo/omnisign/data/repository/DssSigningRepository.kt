package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.enums.toDomainOrNull
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.localizableText
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.repository.*
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.SignatureAlgorithm
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.SignatureValue
import eu.europa.esig.dss.model.ToBeSigned
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.SignatureImageParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.spi.validation.CertificateVerifier
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.DSSPrivateKeyEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import java.io.File
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm as DssEncryptionAlgorithm
import eu.europa.esig.dss.enumerations.SignatureLevel as DssSignatureLevel

/**
 * JVM implementation of [SigningRepository] backed by the EU DSS library.
 *
 * Handles the complete PAdES signing flow:
 * - Certificate and token selection
 * - Optional RFC 3161 timestamp embedding
 * - Optional visible signature appearance
 * - CRL/OCSP revocation data for B-LT and B-LTA levels
 * - PdfBox memory-efficient document handling
 *
 * [SigningResult.signatureLevel] is read back out of the signed bytes with [readPadesLevel] rather
 * than echoed from the request, so a document that fell short of the level it was signed at — most
 * often a B-LT request whose revocation data could not be fetched — is reported as what it is. A
 * signing tool that overstated a conformance level would be worse than one that declined to state
 * it, and the request alone is not evidence of anything.
 */
class DssSigningRepository(
	private val tokenService: TokenService,
	private val configRepository: ConfigRepository,
	private val credentialStore: CredentialStore,
	private val dssServiceFactory: DssServiceFactory,
	private val algorithmExpirationChecker: AlgorithmExpirationChecker,
	private val warningSanitizer: DssWarningSanitizer,
	private val tspErrorDetector: TspErrorDetector,
	private val trustStore: TrustStore,
	private val documentInputErrorDetector: DocumentInputErrorDetector,
	private val sessionCache: Pkcs11SessionCache,
	private val signatureSpaceErrorDetector: SignatureSpaceErrorDetector,
	private val logCapture: DssLogCapture,
) : SigningRepository {
	
	private var discoveredTokens: List<TokenInfo> = emptyList()
	
	/**
	 * Stored PINs that a token slot has refused, kept so that they are not submitted to it again;
	 * see [loginWithStoredPin].
	 */
	private val refusedStoredPins = ConcurrentHashMap<StoredPinSlot, Sensitive<String>>()

	/**
	 * One lock per token slot, held while a stored PIN is submitted to it; see [loginWithStoredPin].
	 */
	private val storedPinLocks = ConcurrentHashMap<StoredPinSlot, Mutex>()
	
	/**
	 * Sign the document in [parameters] with the key they select, at the level and with the hash and
	 * encryption algorithms that they or the resolved configuration choose. When neither names an
	 * encryption algorithm, the one the key implies signs, and an EdDSA key signs with the hash its
	 * curve fixes, whatever hash they choose.
	 *
	 * Fails with [ConfigurationError.InvalidConfiguration] before any key is looked up when the hash
	 * algorithm, or an encryption algorithm that [parameters] or the configuration names, is disabled
	 * in the resolved configuration, and once the key is known when the encryption algorithm the key
	 * implies, or the hash an EdDSA key's curve fixes, is disabled there; [signatureAlgorithmFor]
	 * lists the other refusals that need the key. Only the resolved configuration's disabled sets
	 * count: the per-request sets on [SigningParameters] are for the web target, which sends them to
	 * the server.
	 *
	 * Fails with [SigningError.ExpiredAlgorithm] before any key is looked up when the hash algorithm
	 * is past its expiration date under a FAIL level, and once the key is known when the hash an
	 * EdDSA key's curve fixes is; under a WARN level, either signs with a warning instead.
	 *
	 * Fails with [SigningError.CertificateRevoked] before the key signs when the revocation data
	 * fetched for the signing certificate's chain shows the certificate, or one above it, revoked or
	 * suspended; see [signingChainRevocation]. The check is skipped when the resolved configuration
	 * turns revocation checking off, as the verifier then has no revocation source. It runs before
	 * the DSS log capture starts, so that what DSS logs while it fetches the data, such as that the
	 * chain is untrusted or that no data could be found, does not become a warning: B-B and B-T
	 * signing fetched no revocation data before, and at B-LT and B-LTA DSS fetches it again while it
	 * embeds it, and logs the same then.
	 *
	 * Fails with [SigningError.TimestampError] when the timestamp server, which signing at B-T and
	 * above calls, cannot be reached, refuses the request, an HTTP error status included, or answers
	 * with something other than a timestamp token. [TspErrorDetector] attributes the failure to the
	 * server whose URL the resolved configuration gives, and its message names that server.
	 */
	@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	override suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult> {
		var tsaUrl: String? = null
		return try {
			val document = InMemoryDocument(parameters.inputBytes, parameters.inputName)
			
			val resolvedConfigResult = resolveConfig(parameters)
			if (resolvedConfigResult.isLeft()) return resolvedConfigResult.leftOrNull()!!.left()
			val resolvedConfig = resolvedConfigResult.getOrNull()!!
			tsaUrl = resolvedConfig.timestampServer?.url
			val effectiveLevel = parameters.signatureLevel ?: resolvedConfig.signatureLevel
			val effectiveHash = parameters.hashAlgorithm ?: resolvedConfig.hashAlgorithm
			val effectiveEncryption = parameters.encryptionAlgorithm ?: resolvedConfig.encryptionAlgorithm
			disabledHashError(effectiveHash, resolvedConfig)?.let { return it.left() }
			effectiveEncryption?.let { disabledEncryptionError(it, resolvedConfig) }?.let { return it.left() }
			val dssSignatureLevel = effectiveLevel.toDss()

			if (effectiveEncryption != null && !effectiveEncryption.isCompatibleWith(effectiveHash)) {
				return SigningError.hashEncryptionIncompatible(
					hash = effectiveHash.name,
					encryption = effectiveEncryption.name,
					compatibleHashes = effectiveEncryption.compatibleHashAlgorithms.joinToString { it.name },
				).left()
			}

			val signingWarnings = mutableListOf<String>()
			expiredHashError(effectiveHash, resolvedConfig, signingWarnings)?.let { return it.left() }

			val requiresTimestamp = parameters.addTimestamp || effectiveLevel != SignatureLevel.PADES_BASELINE_B
			if (requiresTimestamp && resolvedConfig.timestampServer == null) {
				return SigningError.TimestampError(
					text = LocalizableText.Literal(
						"A timestamp server (TSA) must be configured to sign at level ${effectiveLevel.name}. " +
								"Use 'omnisign config set --timestamp-url <url>' or supply '--timestamp-url' for this operation."
					)
				).left()
			}
			
			if (!documentInputErrorDetector.looksLikePdf(parameters.inputBytes)) {
				return SigningError.malformedDocument(details = "input has no %PDF- header").left()
			}

			val resolvedKey = if (parameters.keystoreFile != null) {
				resolveKeyFromKeystore(parameters).getOrElse { return it.left() }
			} else {
				resolvePrivateKey(parameters)
					?: return (parameters.certificateAlias
						?.let { SigningError.noCertificateFoundForAlias(it) }
						?: SigningError.noCertificateFound()).left()
			}
			
			val tokenConnection = resolvedKey.token
			try {
				if (resolvedKey.tokenType == TokenType.WINDOWS_MY && !effectiveHash.isMscapiCompatible) {
					return SigningError.hashNotSupportedByWindowsStore(
						hash = effectiveHash.name,
						details = "Windows CNG only supports SHA-256, SHA-384 and SHA-512 for ECDSA and RSA " +
								"signing with certificate store keys. " +
								"Change the hash algorithm to SHA256, SHA384, or SHA512 in your profile or with --hash.",
					).left()
				}
				
				val privateKey = resolvedKey.privateKey
				val signatureAlgorithm = signatureAlgorithmFor(
					privateKey, effectiveEncryption, effectiveHash, resolvedKey.tokenType, resolvedConfig, signingWarnings,
				).getOrElse { return it.left() }
				val statusAlert = CollectingStatusAlert()
				val (service, verifier, tlWarnings) = buildSigningService(
					resolvedConfig,
					dssSignatureLevel,
					parameters.addTimestamp,
					statusAlert
				)
				signingWarnings += tlWarnings
				val signatureParams = buildSignatureParameters(privateKey, signatureAlgorithm, dssSignatureLevel, parameters)
				if (resolvedConfig.validation.checkRevocation != false) {
					signingChainRevocation(signatureParams, verifier)?.let { return certificateRevokedError(it).left() }
				}
				val certIdNames = buildCertIdNames(privateKey)
				val logSession = logCapture.start()
				try {
					val dataToSign: ToBeSigned = service.getDataToSign(document, signatureParams)
					val signatureValue: SignatureValue =
						tokenConnection.sign(dataToSign, signatureParams.signatureAlgorithm, privateKey)
					val signedDocument = service.signDocument(document, signatureParams, signatureValue)
					
					signingWarnings += statusAlert.drain()
					signingWarnings += logSession.stop()
					
					val sanitized =
						warningSanitizer.sanitize(signingWarnings, certIdNames, SIGNING_SUPPRESSED_CATEGORIES)
					
					val outputBytes = withContext(Dispatchers.IO) {
						signedDocument.openStream().use { it.readAllBytes() }
					}
					
					val achievedLevel =
						readPadesLevel(outputBytes, verifier, dssServiceFactory.buildPdfObjectFactory())

					SigningResult(
						outputBytes = outputBytes,
						outputName = parameters.inputName,
						signatureId = extractSignatureId(parameters.inputName),
						signatureLevel = (achievedLevel ?: effectiveLevel).name,
						annotatedWarnings = sanitized.annotatedSummaries,
						rawWarnings = sanitized.raw,
						hasRevocationWarnings = sanitized.hasRevocationWarnings,
					).right()
				} finally {
					logSession.stop()
				}
			} finally {
				if (!resolvedKey.cached) tokenConnection.close()
			}
		} catch (e: Exception) {
			if (tspErrorDetector.isTspException(e) || tspErrorDetector.isTimestampRequestFailure(e, tsaUrl)) {
				SigningError.TimestampError(
					text = LocalizableText.Literal(tspErrorDetector.buildUserMessage(e, tsaUrl)),
					details = e.message,
					cause = e,
				).left()
			} else if (documentInputErrorDetector.isEncrypted(e)) {
				SigningError.pdfEncrypted(details = e.message, cause = e).left()
			} else if (documentInputErrorDetector.isLocked(e)) {
				SigningError.pdfLocked(details = e.message, cause = e).left()
			} else if (signatureSpaceErrorDetector.isSignatureTooLarge(e)) {
				SigningError.signatureTooLarge(details = e.message, cause = e).left()
			} else {
				SigningError.signingFailed(details = e.message, cause = e).left()
			}
		}
	}
	
	@Suppress("TooGenericExceptionCaught")
	override suspend fun listAvailableCertificates(
		promptForLocked: Boolean,
	): OperationResult<CertificateDiscoveryResult> {
		return try {
			val tokensResult = tokenService.discoverTokens()
			tokensResult.fold(
				ifLeft = { return it.left() },
				ifRight = { tokens ->
					discoveredTokens = tokens
					
					val partialResults = coroutineScope {
						tokens.map { token -> async { discoverTokenCertificatesSilent(token) } }.awaitAll()
					}
					val certificates = partialResults.flatMap { it.certificates }.toMutableList()
					val warnings = partialResults.flatMap { it.tokenWarnings }.toMutableList()
					val locked = partialResults.flatMap { it.lockedTokens }
					
					val stillLocked = mutableListOf<LockedTokenInfo>()
					if (promptForLocked && locked.isNotEmpty()) {
						val tokensById = tokens.associateBy { it.id }
						for (entry in locked) {
							val token = tokensById[entry.tokenId]
							if (token == null) {
								stillLocked.add(entry)
								continue
							}
							tokenService.loadCertificates(token, null).fold(
								ifLeft = { stillLocked.add(entry) },
								ifRight = { certs -> certificates.addAll(certs.toAvailableCertificateInfoList(token)) },
							)
						}
					} else {
						stillLocked.addAll(locked)
					}
					
					CertificateDiscoveryResult(
						certificates = certificates,
						tokenWarnings = warnings,
						lockedTokens = stillLocked,
					).right()
				}
			)
		} catch (e: Exception) {
			SigningError.listCertificatesFailed(details = e.message, cause = e).left()
		}
	}
	
	/**
	 * Probe and enumerate certificates for a single [token] without prompting for credentials.
	 *
	 * Returns a partial [CertificateDiscoveryResult] containing only the data relevant to
	 * this token.  Results from all tokens are merged by [listAvailableCertificates] after
	 * all parallel probes complete; the optional prompted pass then re-attempts any
	 * `lockedTokens` it produced.
	 *
	 * A PIN-protected token with a PIN stored for it is logged in to with that PIN through
	 * [loginWithStoredPin] and reported as locked when the login fails. A stored PIN that the
	 * token's slot refuses, now or before, is not submitted to it again; the token is then listed
	 * as though no PIN were stored.
	 *
	 * PIN-protected tokens without a usable stored credential are listed by [discoverWithoutLogin].
	 * Tokens that are physically absent are silently skipped. Load errors for PINless tokens
	 * (e.g., OS key stores) are reported as warnings so the user can diagnose the issue.
	 */
	@Suppress("TooGenericExceptionCaught")
	private suspend fun discoverTokenCertificatesSilent(token: TokenInfo): CertificateDiscoveryResult {
		if (!tokenService.probeTokenPresent(token)) {
			return CertificateDiscoveryResult(certificates = emptyList())
		}
		if (token.requiresPin) {
			val storedPin = credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, token.id)
			val loaded = storedPin?.let { pin ->
				loginWithStoredPin(token, pin, refusalIn = { it.leftOrNull()?.cause }) {
					tokenService.loadCertificatesSilent(token, pin)
				}
			} ?: return discoverWithoutLogin(token)
			return loaded.fold(
				ifLeft = {
					CertificateDiscoveryResult(
						certificates = emptyList(),
						lockedTokens = listOf(LockedTokenInfo(token.id, token.name, token.type.name)),
					)
				},
				ifRight = { certs ->
					CertificateDiscoveryResult(certificates = certs.toAvailableCertificateInfoList(token))
				},
			)
		}
		return tokenService.loadCertificatesSilent(token, null).fold(
			ifLeft = { error ->
				CertificateDiscoveryResult(
					certificates = emptyList(),
					tokenWarnings = listOf(
						TokenDiscoveryWarning(
							tokenId = token.id,
							tokenName = token.name,
							message = error.details?.let { LocalizableText.Literal(it) } ?: error.localizableText(),
							details = error.cause?.cause?.let { deepCause ->
								generateSequence(deepCause) { it.cause }
									.mapNotNull { it.message?.trim() }.firstOrNull { it.isNotBlank() }
									?.takeIf { it != (error.details ?: error.message) }
							},
						)
					),
				)
			},
			ifRight = { certs ->
				CertificateDiscoveryResult(certificates = certs.toAvailableCertificateInfoList(token))
			},
		)
	}
	
	/**
	 * List the certificates of a PIN-protected [token] that has no usable stored PIN, without
	 * logging in to it.
	 *
	 * Tries an unauthenticated listing via [TokenService.listCertificatesNoLogin]: when the token
	 * exposes public certificate objects (verified for Czech qualified tokens) those are returned
	 * so the certificate is selectable immediately and the PIN is deferred to signing, where the
	 * shared deterministic alias resolves the same key.  When that yields nothing — certs are
	 * private, or the probe failed — the token is reported as locked instead.
	 */
	private suspend fun discoverWithoutLogin(token: TokenInfo): CertificateDiscoveryResult {
		val noLoginCerts = tokenService.listCertificatesNoLogin(token)
			.fold(ifLeft = { emptyList() }, ifRight = { it })
		return if (noLoginCerts.isNotEmpty()) {
			CertificateDiscoveryResult(certificates = noLoginCerts.toAvailableCertificateInfoList(token))
		} else {
			CertificateDiscoveryResult(
				certificates = emptyList(),
				lockedTokens = listOf(LockedTokenInfo(token.id, token.name, token.type.name)),
			)
		}
	}

	/**
	 * Submit [pin], the PIN stored for [token], through [login], unless the slot [token] is pinned
	 * to has refused that very PIN before.
	 *
	 * Nobody watches a stored PIN being submitted, so a wrong one would otherwise be sent again on
	 * every listing and every signature until the token blocks its PIN. A refusal — recognised by
	 * [isPinRejection] in an exception [login] throws, or in the failure [refusalIn] finds in its
	 * result — is therefore remembered for the slot, PIN retries being counted per slot, and that
	 * PIN is not submitted to the slot again until the stored value changes or the process
	 * restarts. Logins with a stored PIN hold one lock per slot, so that concurrent requests cannot
	 * each spend a retry on a PIN before the first refusal is known.
	 *
	 * @param token Token, pinned to the slot that [login] logs in to.
	 * @param pin The PIN stored for [token].
	 * @param refusalIn Picks the failure to inspect out of a result of [login], if it holds one.
	 * @param login Logs in to [token] with [pin] and returns what it read.
	 * @return The result of [login], or `null` when the slot refused [pin], before or now.
	 */
	@Suppress("TooGenericExceptionCaught")
	private suspend fun <T> loginWithStoredPin(
		token: TokenInfo,
		pin: String,
		refusalIn: (T) -> Throwable? = { null },
		login: suspend () -> T,
	): T? {
		val slot = StoredPinSlot(token.id, token.pkcs11SlotId)
		return storedPinLocks.computeIfAbsent(slot) { Mutex() }.withLock {
			if (refusedStoredPins[slot]?.value == pin) return@withLock null
			val result = try {
				login()
			} catch (e: Exception) {
				if (!e.isPinRejection()) throw e
				refuseStoredPin(token, slot, pin)
				return@withLock null
			}
			if (refusalIn(result)?.isPinRejection() == true) {
				refuseStoredPin(token, slot, pin)
				return@withLock null
			}
			refusedStoredPins.remove(slot)
			result
		}
	}

	/**
	 * Remember that [slot] of [token] refused [pin], the PIN stored for [token], and say so in the
	 * log, the only place an operator of a signing server learns that the stored PIN has stopped
	 * working.
	 */
	private fun refuseStoredPin(token: TokenInfo, slot: StoredPinSlot, pin: String) {
		refusedStoredPins[slot] = Sensitive(pin)
		logger.warn {
			"Token '${token.name}'${slot.slotId?.let { " (slot $it)" } ?: ""} refused the PIN stored " +
				"for it in the keychain under service '$TOKEN_CREDENTIAL_SERVICE', account '${token.id}'. " +
				"OmniSign will not submit that PIN to it again until the stored PIN changes or OmniSign restarts."
		}
	}

	/**
	 * Whether any slot of [token] has refused [pin], the PIN stored for [token].
	 */
	private fun hasRefusedStoredPin(token: TokenInfo, pin: String): Boolean =
		refusedStoredPins.any { (slot, refused) -> slot.tokenId == token.id && refused.value == pin }

	@Suppress("TooGenericExceptionCaught")
	override suspend fun unlockToken(tokenId: String): OperationResult<List<AvailableCertificateInfo>> {
		return try {
			val token = discoveredTokens.find { it.id == tokenId }
				?: return SigningError.tokenNotFound(tokenId).left()
			val certsResult = tokenService.loadCertificates(token, null)
			certsResult.map { certs -> certs.toAvailableCertificateInfoList(token) }
		} catch (e: Exception) {
			SigningError.unlockTokenFailed(details = e.message, cause = e).left()
		}
	}
	
	@Suppress("TooGenericExceptionCaught")
	override suspend fun loadCertificatesFromFile(filePath: String): OperationResult<List<AvailableCertificateInfo>> {
		return try {
			val file = File(filePath)
			if (!file.exists()) return SigningError.fileNotFound(file.path).left()
			val password = tokenService.requestPassword(
				"Enter password for ${file.name}",
				"PKCS#12 Password Required",
			) ?: return SigningError.passwordEntryCancelled().left()
			keystoreCertificates(file, password)
		} catch (e: Exception) {
			SigningError.loadCertificatesFromFileFailed(details = e.message, cause = e).left()
		}
	}

	@Suppress("TooGenericExceptionCaught")
	override suspend fun listCertificatesFromKeystore(
		keystoreFile: String,
		keystorePassword: Sensitive<String>?,
	): OperationResult<List<AvailableCertificateInfo>> {
		return try {
			val file = File(keystoreFile)
			if (!file.exists()) return SigningError.fileNotFound(file.path).left()
			keystoreCertificates(file, keystorePassword?.value ?: "")
		} catch (e: Exception) {
			SigningError.listCertificatesFailed(details = e.message, cause = e).left()
		}
	}

	/**
	 * The certificates of the PKCS#12 keystore at [file], opened with [password] as the token that
	 * [keystoreTokenInfo] builds for it, so that each alias is the one [resolveKeyFromKeystore]
	 * matches when a document is signed from the same file.
	 */
	private suspend fun keystoreCertificates(
		file: File,
		password: String,
	): OperationResult<List<AvailableCertificateInfo>> {
		val token = keystoreTokenInfo(file)
		return tokenService.loadCertificatesSilent(token, password).map { it.toAvailableCertificateInfoList(token) }
	}

	/**
	 * Resolve the effective [ResolvedConfig] for [parameters], falling back to the stored config.
	 * Returns [OperationResult] so that disabled-algorithm violations propagate as errors.
	 */
	private suspend fun resolveConfig(parameters: SigningParameters): OperationResult<ResolvedConfig> {
		if (parameters.resolvedConfig != null) return parameters.resolvedConfig.right()
		val config = configRepository.getCurrentConfig()
		return ResolvedConfig.resolve(
			global = config.global,
			profile = config.activeProfile?.let { config.profiles[it] },
			operationOverrides = null
		)
	}
	
	/**
	 * Build a [PAdESService] wired with a certificate verifier, PDF factory, and optional TSA.
	 *
	 * Uses [DssServiceFactory.buildSigningCertificateVerifier], which loads EU LOTL and custom
	 * trusted-list sources together with the directly-trusted certificates resolved from the
	 * [TrustStore] for the active scope, so that TSA and certificate chains are properly trusted.
	 *
	 * @param statusAlert A [CollectingStatusAlert] that will capture verifier warnings
	 *   (missing revocation data, uncovered POE, etc.) fired during the signing operation.
	 * @return The wired [PAdESService], the verifier behind it, and any TL-loading warnings.
	 */
	private suspend fun buildSigningService(
		resolvedConfig: ResolvedConfig,
		signatureLevel: DssSignatureLevel,
		addTimestamp: Boolean,
		statusAlert: CollectingStatusAlert,
	): SigningService {
		val anchors = trustStore.resolve(TrustScope.of(resolvedConfig.profileName)).getOrElse { emptyList() }
		val (cv, tlWarnings) = dssServiceFactory.buildSigningCertificateVerifier(
			resolvedConfig,
			anchors
		) { statusAlert }
		val service = PAdESService(cv).apply {
			setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			resolvedConfig.timestampServer
				?.takeIf { addTimestamp || signatureLevel != DssSignatureLevel.PAdES_BASELINE_B }
				?.let { setTspSource(dssServiceFactory.buildTspSource(it)) }
		}
		return SigningService(service, cv, tlWarnings.map { it.english() })
	}

	/**
	 * A [PAdESService] wired for signing, together with the [CertificateVerifier] behind it.
	 *
	 * The verifier is carried out of [buildSigningService] so that the level can be read back out of
	 * the signed bytes under the same trust anchors the signature was made with — see
	 * [readPadesLevel], which explains why anchorless answers are wrong. Reusing this one rather than
	 * building a second costs nothing, since its trusted lists are already composed.
	 *
	 * @property service The service to sign with.
	 * @property verifier The verifier the service validates against.
	 * @property warnings TL-loading warnings, already rendered in English.
	 */
	private data class SigningService(
		val service: PAdESService,
		val verifier: CertificateVerifier,
		val warnings: List<String>,
	)

	/**
	 * Return the first [DSSPrivateKeyEntry] that matches the requested alias, looked for on the
	 * token the alias names, or the first available key of any discovered token when no alias is
	 * requested, together with its [AbstractSignatureTokenConnection] and the source [TokenType].
	 * Returns null when no matching key is found.
	 *
	 * Uses a two-pass strategy to avoid unnecessary PIN prompts:
	 *
	 * 1. **Silent pass** — tries every token that does not require interactive input:
	 *    tokens without a PIN requirement and PIN-protected tokens whose credential is
	 *    already stored in the [CredentialStore].  If the requested certificate is found
	 *     here, the method returns immediately and no PIN dialog is ever shown.  A token
	 *    that refuses its stored PIN, now or on an earlier listing or signature, is left
	 *    for the interactive pass as though no PIN were stored; see [loginWithStoredPin].
	 * 2. **Interactive pass** — reached only when the silent pass did not produce a match.
	 *    Iterates over the PIN-protected tokens that were skipped and prompts the user
	 *    via [TokenService.requestPin].
	 *
	 * The hardware presence of PKCS#11 tokens is probed via [TokenService.probeTokenPresent]
	 * before any PIN prompt.  Tokens whose card is not inserted are silently skipped.
	 * The PIN obtained from the credential store or the user is reused for both
	 * [TokenService.loadCertificatesSilent] and [TokenService.getSigningToken] so it is never
	 * entered twice and never discarded.
	 *
	 * Within each token, [tryResolveFromToken] opens the PKCS#11 slot that actually holds the
	 * selected certificate's private key, which need not be the slot discovery pinned the
	 * [TokenInfo] to on a card that presents several token-present slots.
	 *
	 * When an alias is requested, only the token it names is tried. An alias ends with the id of
	 * the token it was listed from (`…@<token id>`, see
	 * [pkcs11CertAlias][cz.pizavo.omnisign.data.service.pkcs11CertAlias]) and
	 * [selectSigningKeyByAlias] matches it whole, so no other token can hold the certificate.
	 * Trying another PKCS#11 token of the same middleware would open it at
	 * [SigningParameters.certificateSlotId], a slot of the named token, and log in to that slot
	 * with the other token's PIN, spending one of the named token's PIN attempts.
	 */
	private suspend fun resolvePrivateKey(
		parameters: SigningParameters
	): ResolvedKey? {
		val tokens = tokenService.discoverTokens().getOrNull() ?: return null
		val alias = parameters.certificateAlias
		
		val presentTokens = tokens
			.filter { alias == null || alias.endsWith("@${it.id}") }
			.filter { tokenService.probeTokenPresent(it) }
		val deferredPinTokens = mutableListOf<TokenInfo>()
		
		for (tokenInfo in presentTokens) {
			val alreadyUnlocked = tokenInfo.type == TokenType.PKCS11 && sessionCache.get(tokenInfo.id) != null
			val storedPin = if (tokenInfo.requiresPin && !alreadyUnlocked) {
				credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, tokenInfo.id)
					?: run { deferredPinTokens.add(tokenInfo); continue }
			} else {
				null
			}

			val result = tryResolveFromToken(tokenInfo, storedPin ?: "", parameters, passwordIsStored = storedPin != null)
			if (result != null) return result
			if (storedPin != null && hasRefusedStoredPin(tokenInfo, storedPin)) deferredPinTokens.add(tokenInfo)
		}
		
		for (tokenInfo in deferredPinTokens) {
			val password = tokenService.requestPin(tokenInfo) ?: continue
			
			val result = tryResolveFromToken(tokenInfo, password, parameters, passwordIsStored = false)
			if (result != null) return result
		}
		
		return null
	}
	
	/**
	 * Resolve a signing key for [parameters] from [tokenInfo], opening the PKCS#11 slot that
	 * actually holds the selected certificate's private key.
	 *
	 * A card can present more than one token-present slot, yet discovery collapses it to a
	 * single [TokenInfo] pinned to the first slot seen.  Signing must therefore target the
	 * slot of the *chosen* certificate, resolved in two steps:
	 *
	 * 1. The token's pinned slot is tried first, overridden by
	 *    [SigningParameters.certificateSlotId] when the UI supplied it from the selected
	 *    certificate — so the common path needs no extra probe.
	 * 2. Only when that misses (the alias is absent from the pinned slot) *and* the caller
	 *    gave an alias without a slot — e.g. the CLI `--alias` path — the no-login listing of
	 *    every slot that holds this token locates the alias's real slot and resolution is
	 *    retried there.
	 *
	 * Slot overriding applies to PKCS#11 tokens only; other token types resolve unchanged.
	 *
	 * @param passwordIsStored Whether [password] was read from the [CredentialStore] rather than
	 *   entered by the user; see [resolveKeyFromSlot].
	 * @return A [ResolvedKey] when a matching certificate and key are found, null otherwise.
	 */
	private suspend fun tryResolveFromToken(
		tokenInfo: TokenInfo,
		password: String,
		parameters: SigningParameters,
		passwordIsStored: Boolean,
	): ResolvedKey? {
		val primaryToken = parameters.certificateSlotId
			?.takeIf { tokenInfo.type == TokenType.PKCS11 }
			?.let { tokenInfo.copy(pkcs11SlotId = it) }
			?: tokenInfo
		resolveKeyFromSlot(primaryToken, password, parameters, passwordIsStored)?.let { return it }
		
		if (tokenInfo.type != TokenType.PKCS11 ||
			parameters.certificateSlotId != null ||
			parameters.certificateAlias == null
		) {
			return null
		}
		
		val aliasSlot = tokenService.listCertificatesNoLogin(tokenInfo).getOrNull()
			?.firstOrNull { it.alias == parameters.certificateAlias }
			?.pkcs11SlotId
		if (aliasSlot == null || aliasSlot == tokenInfo.pkcs11SlotId) return null
		
		return resolveKeyFromSlot(tokenInfo.copy(pkcs11SlotId = aliasSlot), password, parameters, passwordIsStored)
	}
	
	/**
	 * Resolve a signing key for [tokenInfo] at whatever slot it is pinned to, matching [parameters].
	 *
	 * For a PKCS#11 token already **unlocked** in [sessionCache], the held session's keys are
	 * reused with no further `C_Login` (no secure-pad prompt) and the returned [ResolvedKey] is
	 * marked `cached` so the cache keeps owning the token.  Otherwise the token is opened by
	 * [openAndSelectKey], and a [password] read from the [CredentialStore] is submitted through
	 * [loginWithStoredPin], so a slot that has refused it is not sent it again and `null` is
	 * returned instead.
	 *
	 * The key is matched to [SigningParameters.certificateAlias] by [selectSigningKeyByAlias],
	 * whose alias embeds the serial number, so a slot holding several certificates with the same
	 * subject (e.g. a renewed certificate beside its expired predecessor) still signs with the
	 * chosen one.  When the requested alias is absent — from the cached session's slot or from a
	 * freshly-opened one — the method falls through past the cache (or closes the transient token)
	 * and returns `null`, so [tryResolveFromToken] can retry on the certificate's real slot.
	 *
	 * @param passwordIsStored Whether [password] was read from the [CredentialStore] rather than
	 *   entered by the user.
	 * @return A [ResolvedKey] when a matching certificate and key are present, null otherwise.
	 */
	private suspend fun resolveKeyFromSlot(
		tokenInfo: TokenInfo,
		password: String,
		parameters: SigningParameters,
		passwordIsStored: Boolean,
	): ResolvedKey? {
		if (tokenInfo.type == TokenType.PKCS11) {
			val unlocked = sessionCache.get(tokenInfo.id)
			if (unlocked != null) {
				val key = selectSigningKeyByAlias(unlocked.keys, parameters.certificateAlias, tokenInfo)
				if (key != null) return ResolvedKey(key, unlocked.token, tokenInfo.type, cached = true)
			}
		}

		return if (passwordIsStored) {
			loginWithStoredPin(tokenInfo, password) { openAndSelectKey(tokenInfo, password, parameters) }
		} else {
			openAndSelectKey(tokenInfo, password, parameters)
		}
	}

	/**
	 * Open [tokenInfo] at the slot it is pinned to with [password] and pick the key [parameters]
	 * asks for.
	 *
	 * The token is opened **once** via [TokenService.openSigningToken] and its keys read **once** —
	 * both the certificate selection and the subsequent signature run off that single enumeration —
	 * and the result is marked transient (closed right after signing). The token is closed at once
	 * when it holds no matching key, or when reading its keys fails, as it does when the token
	 * refuses [password].
	 *
	 * @return A transient [ResolvedKey], or null when the token cannot be opened or holds no
	 *   matching key.
	 */
	@Suppress("TooGenericExceptionCaught")
	private suspend fun openAndSelectKey(
		tokenInfo: TokenInfo,
		password: String,
		parameters: SigningParameters,
	): ResolvedKey? {
		val dssToken = tokenService.openSigningToken(tokenInfo, password).getOrNull()
			?.getDssToken() as? AbstractSignatureTokenConnection ?: return null

		val key = try {
			selectSigningKeyByAlias(dssToken.keys, parameters.certificateAlias, tokenInfo)
		} catch (e: Exception) {
			dssToken.close()
			throw e
		}
		if (key == null) {
			dssToken.close()
			return null
		}

		return ResolvedKey(key, dssToken, tokenInfo.type, cached = false)
	}
	
	/**
	 * Build the transient [TokenInfo] for the PKCS#12 keystore at [file].
	 *
	 * Shared by [resolveKeyFromKeystore] (signing) and [keystoreCertificates] (the listing behind
	 * [loadCertificatesFromFile] and [listCertificatesFromKeystore]) so both derive the *same*
	 * [TokenInfo.id] — and therefore the same deterministic `<CN>-<serial>@<id>` alias (see
	 * `pkcs11CertAlias`). This agreement is load-bearing: it is what lets a certificate listed for
	 * the signing dialog be handed straight back as [SigningParameters.certificateAlias], with the
	 * file as [SigningParameters.keystoreFile], and resolve to the same key when signing.
	 */
	private fun keystoreTokenInfo(file: File): TokenInfo = TokenInfo(
		id = "keystore-${file.name}",
		name = file.name,
		type = TokenType.FILE,
		path = file.absolutePath,
		requiresPin = true,
	)

	/**
	 * Resolve a signing key directly from the PKCS#12 keystore at [SigningParameters.keystoreFile],
	 * bypassing token discovery (which only surfaces PKCS#11 and OS-store tokens).
	 *
	 * The keystore password is taken from [SigningParameters.keystorePassword] when supplied;
	 * otherwise an attempt is made with an empty password and, only if that fails because the keystore
	 * is protected, the user is prompted with hidden input via [TokenService.requestPassword].
	 * [SigningParameters.certificateAlias] selects the certificate within the keystore, or its sole /
	 * first key when omitted. The keystore [TokenInfo] is built by [keystoreTokenInfo], shared with
	 * [keystoreCertificates] so the alias a client selects from the listing matches here.
	 */
	private suspend fun resolveKeyFromKeystore(
		parameters: SigningParameters,
	): OperationResult<ResolvedKey> {
		val file = File(parameters.keystoreFile!!)
		if (!file.exists()) return SigningError.fileNotFound(file.path).left()
		val fileToken = keystoreTokenInfo(file)

		val provided = parameters.keystorePassword?.value
		var password = provided ?: ""
		var loaded = tokenService.loadCertificatesSilent(fileToken, password)
		if (loaded.isLeft() && provided == null) {
			password = tokenService.requestPassword(
				"Enter password for keystore ${file.name}",
				"Keystore Password",
			) ?: return SigningError.passwordEntryCancelled().left()
			loaded = tokenService.loadCertificatesSilent(fileToken, password)
		}
		val certs = loaded.getOrElse { return it.left() }
		
		val selected = if (parameters.certificateAlias != null) {
			certs.find { it.alias == parameters.certificateAlias }
				?: return SigningError.noCertificateFoundForAlias(parameters.certificateAlias).left()
		} else {
			certs.firstOrNull() ?: return SigningError.noCertificateFound().left()
		}
		
		val dssToken = tokenService.getSigningToken(selected, password).getOrElse { return it.left() }
			.getDssToken() as? AbstractSignatureTokenConnection
			?: return SigningError.createSigningTokenFailed().left()
		val key = selectSigningKey(dssToken.keys, selected)
			?: return SigningError.noCertificateFound().left()
		return ResolvedKey(key, dssToken, TokenType.FILE, cached = false).right()
	}
	
	/**
	 * Holds the resolved private key entry, its DSS token connection, and the source [TokenType].
	 *
	 * @property cached Whether [token] is an unlocked session owned by [Pkcs11SessionCache]. When
	 *   true the signing flow borrows it and must **not** close it (the cache closes it on card
	 *   removal or shutdown); when false the token is transient and is closed right after signing.
	 */
	private data class ResolvedKey(
		val privateKey: DSSPrivateKeyEntry,
		val token: AbstractSignatureTokenConnection,
		val tokenType: TokenType,
		val cached: Boolean,
	)
	
	/**
	 * A token slot that a stored PIN is submitted to.
	 *
	 * PIN retries are counted per slot, and a card that presents several slots may protect each
	 * with a PIN of its own, so a slot refusing the stored PIN says nothing about the others.
	 *
	 * @property tokenId The token's [TokenInfo.id], which is also the stored PIN's account.
	 * @property slotId The PKCS#11 slot, or null when the token is not pinned to one.
	 */
	private data class StoredPinSlot(val tokenId: String, val slotId: Long?)

	private companion object {
		val logger = KotlinLogging.logger {}

		const val TOKEN_CREDENTIAL_SERVICE = "omnisign-token"

		/**
		 * Reservation for a B-B signature, which carries no timestamp token.
		 */
		const val BARE_SIGNATURE_CONTENT_SIZE = 13_312

		/**
		 * Reservation for every level that carries a signature timestamp (B-T and above).
		 * Below [DssServiceFactory.PDFA_MAX_STRING_LENGTH]; see [contentSizeForLevel] for why it does not grow
		 * with the level.
		 */
		const val TIMESTAMPED_CONTENT_SIZE = 30_720

		/**
		 * The encryption algorithms DSS signs with under JCE names only the BouncyCastle provider
		 * offers, such as `SHA256withRSAandMGF1` and `SHA256withPLAIN-ECDSA`.
		 */
		val BOUNCY_CASTLE_ALGORITHMS = setOf(EncryptionAlgorithm.RSA_SSA_PSS, EncryptionAlgorithm.PLAIN_ECDSA)

		/**
		 * The token types whose keys only their own provider can sign with: SunPKCS11 for a PKCS#11
		 * token, which offers RSASSA-PSS and plain ECDSA under other names than DSS asks for, and
		 * SunMSCAPI for the Windows Certificate Store, whose keys BouncyCastle cannot use.
		 */
		val PROVIDER_BOUND_TOKENS = setOf(TokenType.PKCS11, TokenType.WINDOWS_MY)

		/**
		 * Warning categories suppressed during signing.
		 *
		 * [DssWarningSanitizer.WarningCategory.REVOCATION_NOT_FOUND] is suppressed because the PAdES
		 * extension process embeds revocation data independently of the certificate verifier's
		 * pre-extension check, which runs before anything has been embedded: on a signing operation
		 * that reaches B-LT or B-LTA the warning describes a gap the very next step closes.
		 *
		 * Suppressing it is only defensible because [SigningResult.signatureLevel] is read back out of
		 * the signed bytes with [readPadesLevel] rather than echoed from the request. Should that step
		 * *not* have closed the gap, the reported level says so — the document comes back as B-T — so
		 * hiding the warning hides noise rather than the failure itself.
		 *
		 * This holds only while *signing*. Augmenting an already-signed document has no later step to
		 * close the gap, which is why the archiving path keeps the category — see
		 * [DssServiceFactory.buildExtendCertificateVerifier] and
		 * [DssWarningSanitizer.WarningCategory.blocksLongTermMaterial].
		 *
		 * [DssWarningSanitizer.WarningCategory.FRESH_REVOCATION_MISSING] is suppressed because it is
		 * unavoidable here: it reports revocation data that does not postdate the signature, and no
		 * revocation data in existence can postdate a signature that is only being made now. The
		 * signer has nothing to act on. Validation reports it instead, where it carries real
		 * information — that nothing covering the signature could be obtained *since*, either.
		 *
		 * [DssWarningSanitizer.WarningCategory.CERTIFICATE_PARSE_ERROR] is suppressed because DSS
		 * raises it while parsing non-standard extensions (typically a Subject Alternative Name
		 * `otherName`) on the EU LOTL / trusted-list certificates it consults to establish trust —
		 * third-party material the signer neither owns nor can act on. DSS skips the offending
		 * entry and continues, so the signature is unaffected and the warning carries no actionable
		 * information for the signer.
		 */
		val SIGNING_SUPPRESSED_CATEGORIES = setOf(
			DssWarningSanitizer.WarningCategory.REVOCATION_NOT_FOUND,
			DssWarningSanitizer.WarningCategory.FRESH_REVOCATION_MISSING,
			DssWarningSanitizer.WarningCategory.CERTIFICATE_PARSE_ERROR,
		)
	}
	
	/**
	 * Map a list of [CertificateEntry] to [AvailableCertificateInfo] for the given token.
	 */
	private fun List<CertificateEntry>.toAvailableCertificateInfoList(
		token: TokenInfo,
	): List<AvailableCertificateInfo> = map { cert ->
		AvailableCertificateInfo(
			alias = cert.alias,
			subjectDN = cert.subjectDN,
			issuerDN = cert.issuerDN,
			validFrom = cert.validFrom,
			validTo = cert.validTo,
			tokenType = token.type.name,
			tokenName = token.name,
			keyUsages = cert.keyUsages,
			isQualified = cert.isQualified,
			isQscd = cert.isQscd,
			pkcs11SlotId = cert.pkcs11SlotId,
		)
	}
	
	/**
	 * Build [PAdESSignatureParameters] that sign with [signatureAlgorithm] at [dssLevel], with the
	 * key in [privateKey] and the reason, location, contact and appearance in [parameters]. A box
	 * given neither text nor an image names the signer and shows the signing time these parameters
	 * claim, as [visibleSignatureText] describes.
	 *
	 * The signing certificate is set before the algorithms: setting it resets the encryption
	 * algorithm to the one the certificate's key implies, which would undo a choice such as RSA-PSS
	 * on an RSA key.
	 */
	private fun buildSignatureParameters(
		privateKey: DSSPrivateKeyEntry,
		signatureAlgorithm: SignatureAlgorithm,
		dssLevel: DssSignatureLevel,
		parameters: SigningParameters
	): PAdESSignatureParameters = PAdESSignatureParameters().apply {
		setSignatureLevel(dssLevel)
		signaturePackaging = SignaturePackaging.ENVELOPED
		setSigningCertificate(privateKey.certificate)
		setEncryptionAlgorithm(signatureAlgorithm.encryptionAlgorithm)
		setDigestAlgorithm(signatureAlgorithm.digestAlgorithm)
		certificateChain = privateKey.certificateChain.toMutableList()
		contentSize = contentSizeForLevel(dssLevel)
		dssServiceFactory.applyTimestampContentSize(this)

		parameters.reason?.let { reason = it }
		parameters.location?.let { location = it }
		parameters.contactInfo?.let { contactInfo = it }
		parameters.visibleSignature?.let {
			imageParameters = buildImageParameters(
				it,
				extractSubjectCN(privateKey.certificate.certificate.subjectX500Principal),
				bLevel().signingDate.toKotlinInstant(),
			)
		}
	}

	/**
	 * The signature algorithm the key in [privateKey] signs with: the encryption algorithm
	 * [requested] names, or the one the key implies when it names none, paired with [hash]; for an
	 * EdDSA key, the algorithm of its curve, which [edDsaAlgorithm] picks.
	 *
	 * Fails with [SigningError.SigningFailed] when the key is of a type OmniSign cannot sign with,
	 * such as the key-agreement types X25519 and X448. Fails with [SigningError.InvalidParameters]
	 * when [requested] does not fit the key, as [fitsKey] judges it; when [hash] does not pair with
	 * the algorithm; and when the algorithm is one of [BOUNCY_CASTLE_ALGORITHMS] and the key is on
	 * one of the [PROVIDER_BOUND_TOKENS]. Fails with [ConfigurationError.InvalidConfiguration] when
	 * [config] disables the algorithm, which [signDocument] could check before the key was known
	 * only when one was named. For an EdDSA key, also fails as [edDsaAlgorithm] does.
	 */
	@Suppress("ReturnCount")
	private fun signatureAlgorithmFor(
		privateKey: DSSPrivateKeyEntry,
		requested: EncryptionAlgorithm?,
		hash: HashAlgorithm,
		tokenType: TokenType,
		config: ResolvedConfig,
		warnings: MutableList<String>,
	): OperationResult<SignatureAlgorithm> {
		val publicKey = privateKey.certificate.publicKey
		val keyAlgorithm: DssEncryptionAlgorithm? = DssEncryptionAlgorithm.forName(publicKey.algorithm, null)
		val keyEncryption = keyAlgorithm?.toDomainOrNull()
			?: return SigningError.signingFailed(
				details = "OmniSign cannot sign with a key of type ${keyAlgorithm?.getName() ?: publicKey.algorithm}",
			).left()
		if (requested != null && !fitsKey(requested, keyEncryption)) {
			return SigningError.encryptionDoesNotFitKey(encryption = requested.name, keyAlgorithm = keyEncryption.name).left()
		}
		val encryption = requested ?: keyEncryption
		disabledEncryptionError(encryption, config)?.let { return it.left() }
		if (encryption in BOUNCY_CASTLE_ALGORITHMS && tokenType in PROVIDER_BOUND_TOKENS) {
			return SigningError.encryptionNotSupportedByToken(
				encryption = encryption.name,
				details = "DSS signs ${encryption.name} under a JCE name only the BouncyCastle provider offers, " +
						"and a ${tokenType.name} key can be used only through its own provider",
			).left()
		}
		if (encryption == EncryptionAlgorithm.EDDSA) return edDsaAlgorithm(publicKey, hash, config, warnings)
		return SignatureAlgorithm.getAlgorithm(encryption.toDss(), DigestAlgorithm.forName(hash.dssName))?.right()
			?: SigningError.hashEncryptionIncompatible(
				hash = hash.name,
				encryption = encryption.name,
				compatibleHashes = encryption.compatibleHashAlgorithms.joinToString { it.name },
			).left()
	}

	/**
	 * Whether [requested] can sign with a key whose own algorithm is [keyEncryption]: it has to be of
	 * the key's family, as RSA-PSS is for an RSA key while ECDSA is not. A key whose certificate
	 * marks it for RSASSA-PSS fits RSA-PSS alone, as RFC 4055 allows such a key no other signature.
	 */
	private fun fitsKey(requested: EncryptionAlgorithm, keyEncryption: EncryptionAlgorithm): Boolean =
		requested.toDss().isEquivalent(keyEncryption.toDss()) &&
				(keyEncryption != EncryptionAlgorithm.RSA_SSA_PSS || requested == EncryptionAlgorithm.RSA_SSA_PSS)

	/**
	 * The EdDSA signature algorithm of the curve [publicKey] lies on: Ed25519, which hashes with
	 * SHA-512, or Ed448, which hashes with SHAKE256.
	 *
	 * Fails as [disabledHashError] and [expiredHashError] do when [config] disables the curve's hash
	 * or its date has passed under a FAIL level, with details that say the key fixes the hash, and
	 * adds a warning to [warnings] when its date has passed under a WARN level. A curve hash that is
	 * the configured [hash] is not checked again, as [signDocument] checked it before the key was
	 * known. SHAKE256 is no [HashAlgorithm], so nothing disables or dates it.
	 */
	private fun edDsaAlgorithm(
		publicKey: PublicKey,
		hash: HashAlgorithm,
		config: ResolvedConfig,
		warnings: MutableList<String>,
	): OperationResult<SignatureAlgorithm> {
		val curve = SubjectPublicKeyInfo.getInstance(publicKey.encoded).algorithm.algorithm.id
		val algorithm = SignatureAlgorithm.forOID(curve)
		val curveHash = HashAlgorithm.entries.firstOrNull { DigestAlgorithm.forName(it.dssName) == algorithm.digestAlgorithm }
		if (curveHash == null || curveHash == hash) return algorithm.right()
		val fixedHash = "An ${algorithm.jceId} key signs with ${curveHash.name}, whatever hash algorithm is chosen"
		disabledHashError(curveHash, config, details = "$fixedHash. Enable ${curveHash.name}, or sign with another key.")
			?.let { return it.left() }
		expiredHashError(
			curveHash,
			config,
			warnings,
			remedy = "$fixedHash. Sign with another key, or set --algo-expiration-level WARN to override.",
		)?.let { return it.left() }
		return algorithm.right()
	}

	/**
	 * The error that refuses [hash] when [config] disables it, with [details] when given, or `null`
	 * when [config] does not disable it.
	 */
	private fun disabledHashError(
		hash: HashAlgorithm,
		config: ResolvedConfig,
		details: String? = null,
	): ConfigurationError.InvalidConfiguration? =
		if (hash in config.disabledHashAlgorithms) {
			ConfigurationError.InvalidConfiguration(
				LocalizableText.Literal("Hash algorithm ${hash.name} is disabled in the configuration"),
				details,
			)
		} else {
			null
		}

	/** The error that refuses [encryption] when [config] disables it, or `null` when it does not. */
	private fun disabledEncryptionError(
		encryption: EncryptionAlgorithm,
		config: ResolvedConfig,
	): ConfigurationError.InvalidConfiguration? =
		if (encryption in config.disabledEncryptionAlgorithms) {
			ConfigurationError.InvalidConfiguration(
				LocalizableText.Literal("Encryption algorithm ${encryption.name} is disabled in the configuration")
			)
		} else {
			null
		}

	/**
	 * Check [hash] against the expiration dates of [config]'s validation policy, as of today: the
	 * error that refuses it once its date has passed under a FAIL level, with [remedy] as its
	 * details, or `null`, after adding a warning to [warnings] when its date has passed under a WARN
	 * level.
	 */
	private fun expiredHashError(
		hash: HashAlgorithm,
		config: ResolvedConfig,
		warnings: MutableList<String>,
		remedy: String = "Change the hash algorithm or set --algo-expiration-level WARN to override.",
	): SigningError.ExpiredAlgorithm? {
		val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
		val constraints = config.validation.algorithmConstraints
		return when (algorithmExpirationChecker.check(hash, constraints, today)) {
			AlgorithmStatus.EXPIRED_FAIL -> SigningError.ExpiredAlgorithm(
				text = LocalizableText.Literal(algorithmExpirationChecker.warningMessage(hash, constraints)),
				details = remedy,
			)

			AlgorithmStatus.EXPIRED_WARN -> {
				warnings += algorithmExpirationChecker.warningMessage(hash, constraints)
				null
			}

			AlgorithmStatus.VALID -> null
		}
	}

	/**
	 * The refusal to sign under a chain that [revocation] shows a certificate of revoked or
	 * suspended: [SigningError.CertificateRevoked], naming the certificate by its common name, with
	 * its subject, serial number and issuer and the revocation's date and reason as the details.
	 */
	private fun certificateRevokedError(revocation: RevocationToken<*>): SigningError.CertificateRevoked {
		val certificate = revocation.relatedCertificate.certificate
		return SigningError.certificateRevoked(
			certificate = extractSubjectCN(certificate.subjectX500Principal),
			details = "Subject: ${certificate.subjectX500Principal.name}; " +
					"serial number: ${certificate.serialNumber}; " +
					"issuer: ${certificate.issuerX500Principal.name}; " +
					"revoked on: ${revocation.revocationDate?.toKotlinInstant() ?: "unknown"}; " +
					"reason: ${revocation.reason?.shortName ?: "unspecified"}",
		)
	}

	/**
	 * Returns the `/Contents` reservation in bytes for [level] — the space set aside in the PDF for
	 * the CMS signature before that signature exists.
	 *
	 * A reservation is unavoidable: `/ByteRange` must state exact byte offsets before the document
	 * can be digested, so the size of `/Contents` is fixed before the CMS that fills it has been
	 * produced, and it cannot be shrunk afterwards without moving every following byte and
	 * invalidating the digest just signed. DSS passes the value straight to PDFBox as
	 * `setPreferredSignatureSize`; neither grows it, so a CMS that does not fit fails the save.
	 * The reservation must therefore over-estimate, and the unused tail stays as zero padding.
	 *
	 * **The reservation does not scale with the signature level above B-T.** Only three things ever
	 * occupy `/Contents`: the signing certificate chain, the signature value, and at most one
	 * signature timestamp token. Per ETSI EN 319 142-1 the B-LT validation data goes into the
	 * document-level `/DSS` dictionary, and the B-LTA archive timestamp is a separate signature
	 * dictionary with its own reservation — neither passes through here. Measured payloads: 6,441
	 * bytes for a single self-signed RSA-3072 certificate, 6,965 for a two-certificate RSA-2048
	 * chain, and 8,442 for a real qualified signature (PostSignum chain, RSA-4096, SHA-512,
	 * qualified TSA) of which the timestamp token alone is 5,052.
	 *
	 * The upper bound is a conformance constraint, not a capacity one. PDF/A-1, -2 and -3 inherit
	 * ISO 32000-1 Annex C Table C.1, which caps a string object at [DssServiceFactory.PDFA_MAX_STRING_LENGTH] bytes
	 * (ISO 19005-3 clause 6.1.13, test 3; clause 6.1.12 test 2 for PDF/A-1). Because PDF/A measures
	 * the declared string length rather than its meaningful content, an over-large reservation
	 * alone makes a signed PDF/A document non-conformant even though the signature itself is
	 * unaffected. Any value between the real payload and that cap is equally correct, so
	 * [TIMESTAMPED_CONTENT_SIZE] sits near the top of the window rather than the bottom: an
	 * over-large reservation costs only file bytes, whereas one that is too small fails the save
	 * outright, and does so only for the users whose certificate chain or TSA happens to be larger
	 * than the ones measured here. It leaves roughly 2x headroom over a pessimistic 16 KB estimate
	 * for a three-certificate qualified chain. PDF 2.0 dropped Annex C, so PDF/A-4 has no such
	 * limit — this ceiling exists for PDF/A-1/2/3 inputs.
	 *
	 * @param level The PAdES level being produced.
	 * @return Bytes to reserve, always at most [DssServiceFactory.PDFA_MAX_STRING_LENGTH].
	 */
	private fun contentSizeForLevel(level: DssSignatureLevel): Int = when (level) {
		DssSignatureLevel.PAdES_BASELINE_B -> BARE_SIGNATURE_CONTENT_SIZE
		else -> TIMESTAMPED_CONTENT_SIZE
	}
	
	/**
	 * Build DSS [SignatureImageParameters] from our domain [VisibleSignatureParameters], with the
	 * text [visibleSignatureText] gives a signature by [signerName] at [signingDate].
	 *
	 * @param vsp The appearance the signature was asked for.
	 * @param signerName The name a box without text or image gives the signer.
	 * @param signingDate The signing time the signature claims, which such a box shows.
	 */
	private fun buildImageParameters(
		vsp: VisibleSignatureParameters,
		signerName: String,
		signingDate: Instant,
	): SignatureImageParameters =
		SignatureImageParameters().apply {
			fieldParameters.page = vsp.page
			fieldParameters.originX = vsp.x
			fieldParameters.originY = vsp.y
			fieldParameters.width = vsp.width
			fieldParameters.height = vsp.height
			
			visibleSignatureText(vsp, signerName, signingDate)?.let { textParameters = it }
			vsp.imagePath?.let { path ->
				val imgBytes = File(path).readBytes()
				image = InMemoryDocument(imgBytes)
			}
		}
	
	/**
	 * Derive a stable signature identifier from the output file name and a timestamp.
	 */
	private fun extractSignatureId(documentName: String): String {
		val baseName = documentName.substringBeforeLast('.').ifEmpty { documentName }
		return "sig-$baseName-${System.currentTimeMillis()}"
	}
	
	/**
	 * Build a mapping from DSS certificate identifier (`C-XXXX`) to human-readable
	 * subject name for every certificate in the signing chain.
	 *
	 * The resulting map is passed to [DssWarningSanitizer.sanitize] so that warnings
	 * referencing these IDs can display a friendly name in the UI.
	 */
	private fun buildCertIdNames(privateKey: DSSPrivateKeyEntry): Map<String, String> =
		privateKey.certificateChain
			.associate { it.dssIdAsString to extractSubjectCN(it.certificate.subjectX500Principal) }
}
