package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.repository.CertificateInfo
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.service.TokenService
import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.SignatureLevel
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.model.SignatureValue
import eu.europa.esig.dss.model.ToBeSigned
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import eu.europa.esig.dss.token.DSSPrivateKeyEntry
import java.io.File

/**
 * JVM implementation of SigningRepository using DSS library.
 */
class DssSigningRepository(
	private val tokenService: TokenService,
	private val configRepository: ConfigRepository
) : SigningRepository {
	
	override suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult> {
		return try {
			// Load configuration
			val config = configRepository.getCurrentConfig()
			val resolvedConfig = ResolvedConfig.resolve(
				global = config.global,
				profile = config.activeProfile?.let { config.profiles[it] },
				operationOverrides = null // TODO: Support operation-specific overrides
			)
			
			// Get input document
			val inputFile = File(parameters.inputFile)
			if (!inputFile.exists()) {
				return SigningError.InvalidParameters(
					message = "Input file not found: ${parameters.inputFile}"
				).left()
			}
			
			FileDocument(inputFile)
			
			// Get signing token and certificate
			// TODO: Implement certificate selection and token connection
			// For now, this is a placeholder
			
			File(parameters.outputFile)
			
			// TODO: Implement actual signing logic with DSS
			// This would involve:
			// 1. Getting the token and private key
			// 2. Setting up signature parameters
			// 3. Computing the signature
			// 4. Applying the signature to the document
			
			SigningResult(
				outputFile = parameters.outputFile,
				signatureId = "sig-placeholder",
				signatureLevel = resolvedConfig.signatureLevel.dssName
			).right()
			
		} catch (e: Exception) {
			SigningError.SigningFailed(
				message = "Signing failed",
				details = e.message,
				cause = e
			).left()
		}
	}
	
	override suspend fun listAvailableCertificates(): OperationResult<List<CertificateInfo>> {
		return try {
			// Discover tokens
			val tokensResult = tokenService.discoverTokens()
			tokensResult.fold(
				ifLeft = { error -> return error.left() },
				ifRight = { tokens ->
					val allCertificates = mutableListOf<CertificateInfo>()
					
					// Load certificates from each token
					for (token in tokens) {
						tokenService.loadCertificates(token, null).fold(
							ifLeft = { /* Skip tokens that fail to load */ },
							ifRight = { certs ->
								allCertificates.addAll(certs.map { cert ->
									CertificateInfo(
										alias = cert.alias,
										subjectDN = cert.subjectDN,
										issuerDN = cert.issuerDN,
										validFrom = cert.validFrom,
										validTo = cert.validTo,
										tokenType = token.type.name
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
	 * Helper to perform the actual signing with DSS.
	 */
	private fun performSigning(
		document: DSSDocument,
		privateKey: DSSPrivateKeyEntry,
		token: AbstractSignatureTokenConnection,
		parameters: SigningParameters,
		digestAlgorithm: DigestAlgorithm,
		signatureLevel: SignatureLevel
	): DSSDocument {
		// Create signature parameters
		val signatureParams = PAdESSignatureParameters().apply {
			setSignatureLevel(signatureLevel)
			signaturePackaging = SignaturePackaging.ENVELOPED
			setDigestAlgorithm(digestAlgorithm)
			setSigningCertificate(privateKey.certificate)
			certificateChain = privateKey.certificateChain.toMutableList()
			
			// Set optional parameters
			parameters.reason?.let { reason = it }
			parameters.location?.let { location = it }
			parameters.contactInfo?.let { contactInfo = it }
			
			// TODO: Handle visible signature parameters
		}
		
		// Create service
		val certificateVerifier = CommonCertificateVerifier()
		val service = PAdESService(certificateVerifier)
		
		// Get data to be signed
		val dataToSign: ToBeSigned = service.getDataToSign(document, signatureParams)
		
		// Sign the data
		val signatureValue: SignatureValue = token.sign(dataToSign, digestAlgorithm, privateKey)
		
		// Apply signature to document
		return service.signDocument(document, signatureParams, signatureValue)
	}
}







