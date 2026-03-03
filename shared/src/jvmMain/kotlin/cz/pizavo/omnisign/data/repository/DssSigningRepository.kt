package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
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
			
			val (privateKey, tokenConnection) = resolvePrivateKey(parameters)
				?: return SigningError.TokenAccessError(
					message = "No suitable certificate found${parameters.certificateAlias?.let { " for alias '$it'" } ?: ""}"
				).left()
			
			val requiresTimestamp = parameters.addTimestamp || effectiveLevel != SignatureLevel.PADES_BASELINE_B
			if (requiresTimestamp && resolvedConfig.timestampServer == null) {
				return SigningError.TimestampError(
					message = "A timestamp server (TSA) must be configured to sign at level ${effectiveLevel.name}. " +
							"Use 'omnisign config set --timestamp-url <url>' or supply '--timestamp-url' for this operation."
				).left()
			}
			
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
	override suspend fun listAvailableCertificates(): OperationResult<List<AvailableCertificateInfo>> {
		return try {
			val tokensResult = tokenService.discoverTokens()
			tokensResult.fold(
				ifLeft = { return it.left() },
				ifRight = { tokens ->
					val allCertificates = mutableListOf<AvailableCertificateInfo>()
					for (token in tokens) {
						val certsResult = tokenService.loadCertificatesSilent(token, null)
						certsResult.fold(
							ifLeft = { },
							ifRight = { certs ->
							allCertificates.addAll(certs.map { cert ->
									AvailableCertificateInfo(
										alias = cert.alias,
										subjectDN = cert.subjectDN,
										issuerDN = cert.issuerDN,
										validFrom = cert.validFrom,
										validTo = cert.validTo,
										tokenType = token.type.name,
										keyUsages = cert.keyUsages
									)
								})
							}
						)
					}
					allCertificates.right()
				}
			)
		} catch (e: Exception) {
			SigningError.TokenAccessError(
				message = "Failed to list certificates",
				details = e.message,
				cause = e
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
	 * with its [AbstractSignatureTokenConnection].  Returns null when no matching key is found.
	 *
	 * For tokens that require a PIN (e.g. PKCS#11, file-based keystores), the password is
	 * requested via [cz.pizavo.omnisign.platform.PasswordCallback] during [TokenService.loadCertificates]
	 * and then reused when creating the signing token connection.
	 */
	private suspend fun resolvePrivateKey(
		parameters: SigningParameters
	): Pair<DSSPrivateKeyEntry, AbstractSignatureTokenConnection>? {
		val tokens = tokenService.discoverTokens().getOrNull() ?: return null
		
		for (tokenInfo in tokens) {
			val certs = tokenService.loadCertificates(tokenInfo, null).getOrNull() ?: continue
			val selected = if (parameters.certificateAlias != null) {
				certs.find { it.alias == parameters.certificateAlias }
			} else {
				certs.firstOrNull()
			} ?: continue
			
			val password = if (tokenInfo.requiresPin) {
				credentialStore.getPassword(TOKEN_CREDENTIAL_SERVICE, tokenInfo.id) ?: ""
			} else {
				""
			}
			
			val dssToken = tokenService.getSigningToken(selected, password).getOrNull()
				?.getDssToken() as? AbstractSignatureTokenConnection ?: continue
			
			val key = dssToken.keys.find { k ->
				k.certificate.certificate.subjectX500Principal.toString() == selected.subjectDN
			} ?: dssToken.keys.firstOrNull() ?: continue
			
			return key to dssToken
		}
		
		return null
	}
	
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
		
		parameters.reason?.let { reason = it }
		parameters.location?.let { location = it }
		parameters.contactInfo?.let { contactInfo = it }
		parameters.visibleSignature?.let { imageParameters = buildImageParameters(it) }
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
