package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.error.localizableText
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.*
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.SignatureValue
import eu.europa.esig.dss.model.ToBeSigned
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.SignatureImageParameters
import eu.europa.esig.dss.pades.SignatureImageTextParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.DSSPrivateKeyEntry
import kotlinx.coroutines.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.File
import kotlin.time.Clock
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
) : SigningRepository {

	private var discoveredTokens: List<TokenInfo> = emptyList()
	
	@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	override suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult> {
		return try {
			val document = InMemoryDocument(parameters.inputBytes, parameters.inputName)
			
			val resolvedConfigResult = resolveConfig(parameters)
			if (resolvedConfigResult.isLeft()) return resolvedConfigResult.leftOrNull()!!.left()
			val resolvedConfig = resolvedConfigResult.getOrNull()!!
			val effectiveLevel = parameters.signatureLevel ?: resolvedConfig.signatureLevel
			val effectiveHash = parameters.hashAlgorithm ?: resolvedConfig.hashAlgorithm
			val effectiveEncryption = parameters.encryptionAlgorithm ?: resolvedConfig.encryptionAlgorithm
			val digestAlgorithm = DigestAlgorithm.forName(effectiveHash.dssName)
			val dssSignatureLevel = effectiveLevel.toDss()
			
			if (effectiveEncryption != null && !effectiveEncryption.isCompatibleWith(effectiveHash)) {
				return SigningError.hashEncryptionIncompatible(
					hash = effectiveHash.name,
					encryption = effectiveEncryption.name,
					compatibleHashes = effectiveEncryption.compatibleHashAlgorithms.joinToString { it.name },
				).left()
			}
			
			val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
			val constraints = resolvedConfig.validation.algorithmConstraints
			val signingWarnings = mutableListOf<String>()
			when (algorithmExpirationChecker.check(effectiveHash, constraints, today)) {
				AlgorithmStatus.EXPIRED_FAIL -> return SigningError.ExpiredAlgorithm(
					text = LocalizableText.Literal(algorithmExpirationChecker.warningMessage(effectiveHash, constraints)),
					details = "Change the hash algorithm or set --algo-expiration-level WARN to override."
				).left()
				
				AlgorithmStatus.EXPIRED_WARN ->
					signingWarnings += algorithmExpirationChecker.warningMessage(effectiveHash, constraints)
				
				AlgorithmStatus.VALID -> Unit
			}
			
			val requiresTimestamp = parameters.addTimestamp || effectiveLevel != SignatureLevel.PADES_BASELINE_B
			if (requiresTimestamp && resolvedConfig.timestampServer == null) {
				return SigningError.TimestampError(
					text = LocalizableText.Literal(
						"A timestamp server (TSA) must be configured to sign at level ${effectiveLevel.name}. " +
								"Use 'omnisign config set --timestamp-url <url>' or supply '--timestamp-url' for this operation."
					)
				).left()
			}
			
			val resolvedKey = resolvePrivateKey(parameters)
				?: return (parameters.certificateAlias
					?.let { SigningError.noCertificateFoundForAlias(it) }
					?: SigningError.noCertificateFound()).left()
			
			if (resolvedKey.tokenType == TokenType.WINDOWS_MY && !effectiveHash.isMscapiCompatible) {
				return SigningError.hashNotSupportedByWindowsStore(
					hash = effectiveHash.name,
					details = "Windows CNG only supports SHA-256, SHA-384 and SHA-512 for ECDSA and RSA " +
							"signing with certificate store keys. " +
							"Change the hash algorithm to SHA256, SHA384, or SHA512 in your profile or with --hash.",
				).left()
			}
			
			val privateKey = resolvedKey.privateKey
			val tokenConnection = resolvedKey.token
			if (!documentInputErrorDetector.looksLikePdf(parameters.inputBytes)) {
				return SigningError.malformedDocument(details = "input has no %PDF- header").left()
			}
			val statusAlert = CollectingStatusAlert()
			val logCapture = DssLogCapture()
			val (service, tlWarnings) = buildSigningService(resolvedConfig, dssSignatureLevel, parameters.addTimestamp, statusAlert)
			signingWarnings += tlWarnings
			val signatureParams = buildSignatureParameters(
				privateKey, digestAlgorithm, dssSignatureLevel, effectiveEncryption?.toDss(), parameters
			)
			val certIdNames = buildCertIdNames(privateKey)
			logCapture.start()
			try {
				val dataToSign: ToBeSigned = service.getDataToSign(document, signatureParams)
				val signatureValue: SignatureValue = tokenConnection.sign(dataToSign, digestAlgorithm, privateKey)
				val signedDocument = service.signDocument(document, signatureParams, signatureValue)

				signingWarnings += statusAlert.drain()
				signingWarnings += logCapture.stop()

				val sanitized = warningSanitizer.sanitize(signingWarnings, certIdNames, SIGNING_SUPPRESSED_CATEGORIES)

				val outputBytes = withContext(Dispatchers.IO) {
					signedDocument.openStream().use { it.readAllBytes() }
				}

				SigningResult(
					outputBytes = outputBytes,
					outputName = parameters.inputName,
					signatureId = extractSignatureId(parameters.inputName),
					signatureLevel = effectiveLevel.name,
					annotatedWarnings = sanitized.annotatedSummaries,
					rawWarnings = sanitized.raw,
					hasRevocationWarnings = sanitized.hasRevocationWarnings,
				).right()
			} finally {
				logCapture.stop()
			}
		} catch (e: Exception) {
			if (tspErrorDetector.isTspException(e)) {
				val tsaUrl = resolveConfig(parameters).getOrNull()?.timestampServer?.url
				SigningError.TimestampError(
					text = LocalizableText.Literal(tspErrorDetector.buildUserMessage(e, tsaUrl)),
					details = e.message,
					cause = e,
				).left()
			} else if (documentInputErrorDetector.isEncrypted(e)) {
				SigningError.pdfEncrypted(details = e.message, cause = e).left()
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

					// Pass 1: silent (parallel) — every token gets a non-blocking attempt.
					// PIN-required tokens without a stored credential surface as `lockedTokens`.
					val partialResults = coroutineScope {
						tokens.map { token -> async { discoverTokenCertificatesSilent(token) } }.awaitAll()
					}
					val certificates = partialResults.flatMap { it.certificates }.toMutableList()
					val warnings = partialResults.flatMap { it.tokenWarnings }.toMutableList()
					val locked = partialResults.flatMap { it.lockedTokens }

					// Pass 2: prompted (sequential) — when allowed, ask the platform PasswordCallback
					// for each locked token in turn.  Sequential so simultaneous PIN dialogs / terminal
					// prompts cannot collide.  Tokens whose prompt is cancelled remain locked.
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
	 * PIN-protected tokens without a stored credential first attempt an unauthenticated
	 * listing via [TokenService.listCertificatesNoLogin]: when the token exposes public
	 * certificate objects (verified for Czech qualified tokens) those are returned so the
	 * certificate is selectable immediately and the PIN is deferred to signing, where the
	 * shared deterministic alias resolves the same key.  When that yields nothing — certs
	 * are private, or the probe failed — the token falls back to being reported as locked,
	 * so behaviour only ever improves over the previous always-locked path.  Tokens that
	 * are physically absent are silently skipped. Load errors for PINless tokens (e.g.,
	 * OS key stores) are reported as warnings so the user can diagnose the issue.
	 */
	@Suppress("TooGenericExceptionCaught")
	private suspend fun discoverTokenCertificatesSilent(token: TokenInfo): CertificateDiscoveryResult {
		if (!tokenService.probeTokenPresent(token)) {
			return CertificateDiscoveryResult(certificates = emptyList())
		}
		if (token.requiresPin) {
			val storedPassword = credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, token.id)
			if (storedPassword == null) {
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
			return tokenService.loadCertificatesSilent(token, storedPassword).fold(
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
			val password = tokenService.requestPassword(
				"Enter password for ${File(filePath).name}",
				"PKCS#12 Password Required",
			) ?: return SigningError.passwordEntryCancelled().left()

			tokenService.loadCertificatesFromFile(filePath, password).map { certs ->
				certs.map { cert ->
					AvailableCertificateInfo(
						alias = cert.alias,
						subjectDN = cert.subjectDN,
						issuerDN = cert.issuerDN,
						validFrom = cert.validFrom,
						validTo = cert.validTo,
						tokenType = TokenType.FILE.name,
						tokenName = File(filePath).name,
						keyUsages = cert.keyUsages,
					)
				}
			}
		} catch (e: Exception) {
			SigningError.loadCertificatesFromFileFailed(details = e.message, cause = e).left()
		}
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
	 * @return A pair of the wired [PAdESService] and any TL-loading warnings.
	 */
	private suspend fun buildSigningService(
		resolvedConfig: ResolvedConfig,
		signatureLevel: DssSignatureLevel,
		addTimestamp: Boolean,
		statusAlert: CollectingStatusAlert,
	): Pair<PAdESService, List<String>> {
		val anchors = trustStore.resolve(TrustScope.of(resolvedConfig.profileName)).getOrElse { emptyList() }
		val (cv, tlWarnings) = dssServiceFactory.buildSigningCertificateVerifier(resolvedConfig, anchors) { statusAlert }
		val service = PAdESService(cv).apply {
			setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			resolvedConfig.timestampServer
				?.takeIf { addTimestamp || signatureLevel != DssSignatureLevel.PAdES_BASELINE_B }
				?.let { setTspSource(dssServiceFactory.buildTspSource(it)) }
		}
		return service to tlWarnings
	}
	
	/**
	 * Iterate all discovered tokens and return the first [DSSPrivateKeyEntry] that matches
	 * the requested alias (or the first available key when no alias is requested), together
	 * with its [AbstractSignatureTokenConnection] and the source [TokenType].
	 * Returns null when no matching key is found.
	 *
	 * Uses a two-pass strategy to avoid unnecessary PIN prompts:
	 *
	 * 1. **Silent pass** — tries every token that does not require interactive input:
	 *    tokens without a PIN requirement and PIN-protected tokens whose credential is
	 *    already stored in the [CredentialStore].  If the requested certificate is found
	 *     here, the method returns immediately and no PIN dialog is ever shown.
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
	 */
	private suspend fun resolvePrivateKey(
		parameters: SigningParameters
	): ResolvedKey? {
		val tokens = tokenService.discoverTokens().getOrNull() ?: return null
		
		val presentTokens = tokens.filter { tokenService.probeTokenPresent(it) }
		val deferredPinTokens = mutableListOf<TokenInfo>()
		
		for (tokenInfo in presentTokens) {
			val password = if (tokenInfo.requiresPin) {
				credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, tokenInfo.id)
					?: run { deferredPinTokens.add(tokenInfo); continue }
			} else {
				""
			}
			
			val result = tryResolveFromToken(tokenInfo, password, parameters)
			if (result != null) return result
		}
		
		for (tokenInfo in deferredPinTokens) {
			val password = tokenService.requestPin(tokenInfo) ?: continue
			
			val result = tryResolveFromToken(tokenInfo, password, parameters)
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
	 *    gave an alias without a slot — e.g. the CLI `--alias` path — the all-slots no-login
	 *    probe locates the alias's real slot and resolution is retried there.
	 *
	 * Slot overriding applies to PKCS#11 tokens only; other token types resolve unchanged.
	 *
	 * @return A [ResolvedKey] when a matching certificate and key are found, null otherwise.
	 */
	private suspend fun tryResolveFromToken(
		tokenInfo: TokenInfo,
		password: String,
		parameters: SigningParameters,
	): ResolvedKey? {
		val primaryToken = parameters.certificateSlotId
			?.takeIf { tokenInfo.type == TokenType.PKCS11 }
			?.let { tokenInfo.copy(pkcs11SlotId = it) }
			?: tokenInfo
		resolveKeyFromSlot(primaryToken, password, parameters)?.let { return it }

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

		return resolveKeyFromSlot(tokenInfo.copy(pkcs11SlotId = aliasSlot), password, parameters)
	}

	/**
	 * Load certificates from [tokenInfo] at whatever slot it is pinned to and resolve a
	 * signing key matching [parameters].
	 *
	 * The [CertificateEntry] selected here carries the pinned [TokenInfo], so the DSS token
	 * built by [TokenService.getSigningToken] opens the same slot the certificates were read
	 * from — keeping listing and signing on one slot.  The private key is then matched to the
	 * selected certificate by [selectSigningKey], which keys on issuer and serial so a slot
	 * holding several certificates with the same subject (e.g. a renewed certificate beside its
	 * expired predecessor) still signs with the chosen one.
	 *
	 * @return A [ResolvedKey] when a matching certificate and key are present on that slot,
	 *   null otherwise.
	 */
	private suspend fun resolveKeyFromSlot(
		tokenInfo: TokenInfo,
		password: String,
		parameters: SigningParameters,
	): ResolvedKey? {
		val certs = tokenService.loadCertificatesSilent(tokenInfo, password).getOrNull() ?: return null
		val selected = if (parameters.certificateAlias != null) {
			certs.find { it.alias == parameters.certificateAlias }
		} else {
			certs.firstOrNull()
		} ?: return null

		val dssToken = tokenService.getSigningToken(selected, password).getOrNull()
			?.getDssToken() as? AbstractSignatureTokenConnection ?: return null

		val key = selectSigningKey(dssToken.keys, selected) ?: return null

		return ResolvedKey(key, dssToken, tokenInfo.type)
	}
	
	/**
	 * Holds the resolved private key entry, its DSS token connection, and the source [TokenType].
	 */
	private data class ResolvedKey(
		val privateKey: DSSPrivateKeyEntry,
		val token: AbstractSignatureTokenConnection,
		val tokenType: TokenType,
	)
	
	private companion object {
		const val TOKEN_CREDENTIAL_SERVICE = "omnisign-token"
		
		/**
		 * Warning categories suppressed during signing because the PAdES extension process
		 * embeds revocation data independently of the certificate verifier's pre-extension
		 * check. If the extension fails, DSS throws an exception; if it succeeds, the data
		 * is embedded and these warnings are false positives.
		 */
		val SIGNING_SUPPRESSED_CATEGORIES = setOf(
			DssWarningSanitizer.WarningCategory.REVOCATION_NOT_FOUND,
			DssWarningSanitizer.WarningCategory.FRESH_REVOCATION_MISSING,
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
	 * Build [PAdESSignatureParameters] from the resolved values and optional overrides.
	 *
	 * When [encryptionAlgorithm] is non-null it is applied explicitly, which lets the user
	 * choose between e.g., RSA PKCS#1 v1.5 and RSA-PSS on the same RSA key.
	 * When it is null, DSS derives the encryption algorithm from the certificate key type.
	 */
	private fun buildSignatureParameters(
		privateKey: DSSPrivateKeyEntry,
		digestAlgorithm: DigestAlgorithm,
		dssLevel: DssSignatureLevel,
		encryptionAlgorithm: DssEncryptionAlgorithm?,
		parameters: SigningParameters
	): PAdESSignatureParameters = PAdESSignatureParameters().apply {
		setSignatureLevel(dssLevel)
		signaturePackaging = SignaturePackaging.ENVELOPED
		setDigestAlgorithm(digestAlgorithm)
		encryptionAlgorithm?.let { setEncryptionAlgorithm(it) }
		setSigningCertificate(privateKey.certificate)
		certificateChain = privateKey.certificateChain.toMutableList()
		contentSize = contentSizeForLevel(dssLevel)
		
		parameters.reason?.let { reason = it }
		parameters.location?.let { location = it }
		parameters.contactInfo?.let { contactInfo = it }
		parameters.visibleSignature?.let { imageParameters = buildImageParameters(it) }
	}
	
	/**
	 * Returns the PDF signature content-area reservation in bytes for [level].
	 *
	 * The default DSS value of 9,472 bytes is not enough for any level above B-B because
	 * higher levels embed a certificate chain, CRL/OCSP revocation data, one or more RFC 3161
	 * timestamp tokens, and (for B-LTA) an archive timestamp.  The values below are chosen
	 * with comfortable headroom over the typical content sizes observed in practice:
	 *
	 * | Level    | Budget  | Contains                                          |
	 * |----------|---------|---------------------------------------------------|
	 * | B-B      | 13 KB   | signature and cert chain                          |
	 * | B-T      | 22 KB   | + document timestamp (~5–8 KB)                    |
	 * | B-LT     | 37 KB   | + CRL/OCSP revocation data (~10–15 KB)            |
	 * | B-LTA    | 65 KB   | + archive timestamp and extra revocation (~15 KB) |
	 */
	private fun contentSizeForLevel(level: DssSignatureLevel): Int = when (level) {
		DssSignatureLevel.PAdES_BASELINE_B -> 13_312
		DssSignatureLevel.PAdES_BASELINE_T -> 22_528
		DssSignatureLevel.PAdES_BASELINE_LT -> 37_888
		DssSignatureLevel.PAdES_BASELINE_LTA -> 65_536
		else -> 22_528
	}
	
	/**
	 * Build DSS [SignatureImageParameters] from our domain [VisibleSignatureParameters].
	 */
	private fun buildImageParameters(vsp: VisibleSignatureParameters): SignatureImageParameters =
		SignatureImageParameters().apply {
			fieldParameters.page = vsp.page
			fieldParameters.originX = vsp.x
			fieldParameters.originY = vsp.y
			fieldParameters.width = vsp.width
			fieldParameters.height = vsp.height
			
			vsp.text?.let {
				textParameters = SignatureImageTextParameters().apply { text = it }
			}
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
