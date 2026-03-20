package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.AlgorithmStatus
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.SignatureValue
import eu.europa.esig.dss.model.ToBeSigned
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.SignatureImageParameters
import eu.europa.esig.dss.pades.SignatureImageTextParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.DSSPrivateKeyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.File
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
	private val credentialStore: CredentialStore
) : SigningRepository {
	
	@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	override suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult> {
		return try {
			val inputFile = File(parameters.inputFile)
			if (!inputFile.exists()) {
				return SigningError.InvalidParameters(
					message = "Input file not found: ${parameters.inputFile}"
				).left()
			}
			
			val resolvedConfigResult = resolveConfig(parameters)
			if (resolvedConfigResult.isLeft()) return resolvedConfigResult.leftOrNull()!!.left()
			val resolvedConfig = resolvedConfigResult.getOrNull()!!
			val effectiveLevel = parameters.signatureLevel ?: resolvedConfig.signatureLevel
			val effectiveHash = parameters.hashAlgorithm ?: resolvedConfig.hashAlgorithm
			val effectiveEncryption = parameters.encryptionAlgorithm ?: resolvedConfig.encryptionAlgorithm
			val digestAlgorithm = DigestAlgorithm.forName(effectiveHash.dssName)
			val dssSignatureLevel = effectiveLevel.toDss()
			
			if (effectiveEncryption != null && !effectiveEncryption.isCompatibleWith(effectiveHash)) {
				return SigningError.InvalidParameters(
					message = "Hash algorithm ${effectiveHash.name} is not compatible with " +
							"encryption algorithm ${effectiveEncryption.name}. " +
							"Compatible hash algorithms: ${effectiveEncryption.compatibleHashAlgorithms.joinToString { it.name }}"
				).left()
			}
			
			val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
			val constraints = resolvedConfig.validation.algorithmConstraints
			val signingWarnings = mutableListOf<String>()
			when (AlgorithmExpirationChecker.check(effectiveHash, constraints, today)) {
				AlgorithmStatus.EXPIRED_FAIL -> return SigningError.ExpiredAlgorithm(
					message = AlgorithmExpirationChecker.warningMessage(effectiveHash, constraints),
					details = "Change the hash algorithm or set --algo-expiration-level WARN to override."
				).left()
				
				AlgorithmStatus.EXPIRED_WARN ->
					signingWarnings += AlgorithmExpirationChecker.warningMessage(effectiveHash, constraints)
				
				AlgorithmStatus.VALID -> Unit
			}
			
			val requiresTimestamp = parameters.addTimestamp || effectiveLevel != SignatureLevel.PADES_BASELINE_B
			if (requiresTimestamp && resolvedConfig.timestampServer == null) {
				return SigningError.TimestampError(
					message = "A timestamp server (TSA) must be configured to sign at level ${effectiveLevel.name}. " +
							"Use 'omnisign config set --timestamp-url <url>' or supply '--timestamp-url' for this operation."
				).left()
			}
			
			val resolvedKey = resolvePrivateKey(parameters)
				?: return SigningError.TokenAccessError(
					message = "No suitable certificate found${parameters.certificateAlias?.let { " for alias '$it'" } ?: ""}"
				).left()

			if (resolvedKey.tokenType == TokenType.WINDOWS_MY && !effectiveHash.isMscapiCompatible) {
				return SigningError.InvalidParameters(
					message = "Hash algorithm ${effectiveHash.name} is not supported by the Windows Certificate Store",
					details = "Windows CNG only supports SHA-256, SHA-384 and SHA-512 for ECDSA and RSA " +
						"signing with certificate store keys. " +
						"Change the hash algorithm to SHA256, SHA384, or SHA512 in your profile or with --hash."
				).left()
			}

			val privateKey = resolvedKey.privateKey
			val tokenConnection = resolvedKey.token
			val service = buildSigningService(resolvedConfig, dssSignatureLevel, parameters.addTimestamp)
			val signatureParams = buildSignatureParameters(
				privateKey, digestAlgorithm, dssSignatureLevel, effectiveEncryption?.toDss(), parameters
			)
			val dataToSign: ToBeSigned = service.getDataToSign(FileDocument(inputFile), signatureParams)
			val signatureValue: SignatureValue = tokenConnection.sign(dataToSign, digestAlgorithm, privateKey)
			val signedDocument = service.signDocument(FileDocument(inputFile), signatureParams, signatureValue)
			
			val outputFile = File(parameters.outputFile).also { it.parentFile?.mkdirs() }
			withContext(Dispatchers.IO) { signedDocument.writeTo(outputFile.outputStream()) }
			
			SigningResult(
				outputFile = parameters.outputFile,
				signatureId = extractSignatureId(parameters.outputFile),
				signatureLevel = effectiveLevel.name,
				warnings = signingWarnings
			).right()
		} catch (e: Exception) {
			SigningError.SigningFailed(message = "Signing failed", details = e.message, cause = e).left()
		}
	}

	@Suppress("TooGenericExceptionCaught")
	override suspend fun listAvailableCertificates(): OperationResult<CertificateDiscoveryResult> {
		return try {
			val tokensResult = tokenService.discoverTokens()
			tokensResult.fold(
				ifLeft = { return it.left() },
				ifRight = { tokens ->
					val allCertificates = mutableListOf<AvailableCertificateInfo>()
					val warnings = mutableListOf<TokenDiscoveryWarning>()
					for (token in tokens) {
						if (!tokenService.probeTokenPresent(token)) continue
						val certsResult = tokenService.loadCertificatesSilent(token, null)
						certsResult.fold(
							ifLeft = { error ->
								warnings.add(
									TokenDiscoveryWarning(
										tokenId = token.id,
										tokenName = token.name,
										message = error.details ?: error.message,
										details = error.cause?.cause?.let { deepCause ->
											generateSequence(deepCause) { it.cause }
												.mapNotNull { it.message?.trim() }
												.filter { it.isNotBlank() }
												.firstOrNull()
												?.takeIf { it != (error.details ?: error.message) }
										},
									)
								)
							},
							ifRight = { certs ->
								allCertificates.addAll(certs.map { cert ->
									AvailableCertificateInfo(
										alias = cert.alias,
										subjectDN = cert.subjectDN,
										issuerDN = cert.issuerDN,
										validFrom = cert.validFrom,
										validTo = cert.validTo,
										tokenType = token.type.name,
										keyUsages = cert.keyUsages,
									)
								})
							}
						)
					}
					CertificateDiscoveryResult(
						certificates = allCertificates,
						tokenWarnings = warnings,
					).right()
				}
			)
		} catch (e: Exception) {
			SigningError.TokenAccessError(
				message = "Failed to list certificates",
				details = e.message,
				cause = e,
			).left()
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
	 */
	private fun buildSigningService(
		resolvedConfig: ResolvedConfig,
		signatureLevel: DssSignatureLevel,
		addTimestamp: Boolean
	): PAdESService = PAdESService(DssServiceFactory.buildCertificateVerifier(resolvedConfig)).apply {
		setPdfObjFactory(DssServiceFactory.buildPdfObjectFactory())
		resolvedConfig.timestampServer
			?.takeIf { addTimestamp || signatureLevel != DssSignatureLevel.PAdES_BASELINE_B }
			?.let { setTspSource(DssServiceFactory.buildTspSource(it, credentialStore)) }
	}
	
	/**
	 * Iterate all discovered tokens and return the first [DSSPrivateKeyEntry] that matches
	 * the requested alias (or the first available key when no alias is requested), together
	 * with its [AbstractSignatureTokenConnection] and the source [TokenType].
	 * Returns null when no matching key is found.
	 *
	 * The hardware presence of PKCS#11 tokens is probed via [TokenService.probeTokenPresent]
	 * before any PIN prompt.  Tokens whose card is not inserted are silently skipped.
	 * The PIN obtained from the credential store or the user is reused for both
	 * [TokenService.loadCertificatesSilent] and [TokenService.getSigningToken] so it is never
	 * entered twice and never discarded.
	 */
	private suspend fun resolvePrivateKey(
		parameters: SigningParameters
	): ResolvedKey? {
		val tokens = tokenService.discoverTokens().getOrNull() ?: return null

		for (tokenInfo in tokens) {
			if (!tokenService.probeTokenPresent(tokenInfo)) continue

			val password = if (tokenInfo.requiresPin) {
				credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, tokenInfo.id)
					?: tokenService.requestPin(tokenInfo)
					?: continue
			} else {
				""
			}

			val certs = tokenService.loadCertificatesSilent(tokenInfo, password).getOrNull() ?: continue
			val selected = if (parameters.certificateAlias != null) {
				certs.find { it.alias == parameters.certificateAlias }
			} else {
				certs.firstOrNull()
			} ?: continue

			val dssToken = tokenService.getSigningToken(selected, password).getOrNull()
				?.getDssToken() as? AbstractSignatureTokenConnection ?: continue

			val key = dssToken.keys.find { k ->
				k.certificate.certificate.subjectX500Principal.toString() == selected.subjectDN
			} ?: dssToken.keys.firstOrNull() ?: continue

			return ResolvedKey(key, dssToken, tokenInfo.type)
		}

		return null
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
	 * The default DSS value of 9,472 bytes is insufficient for any level above B-B because
	 * higher levels embed a certificate chain, CRL/OCSP revocation data, one or more RFC 3161
	 * timestamp tokens, and (for B-LTA) an archive timestamp.  The values below are chosen
	 * with comfortable headroom over the typical content sizes observed in practice:
	 *
	 * | Level    | Budget  | Contains                                          |
	 * |----------|---------|---------------------------------------------------|
	 * | B-B      | 13 KB   | signature + cert chain                            |
	 * | B-T      | 22 KB   | + document timestamp (~5–8 KB)                    |
	 * | B-LT     | 37 KB   | + CRL/OCSP revocation data (~10–15 KB)            |
	 * | B-LTA    | 65 KB   | + archive timestamp + extra revocation (~15 KB)   |
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
	private fun extractSignatureId(outputPath: String): String {
		val name = File(outputPath).nameWithoutExtension
		return "sig-$name-${System.currentTimeMillis()}"
	}
}
