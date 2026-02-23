package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * JVM implementation of [ArchivingRepository] backed by the EU DSS library.
 *
 * Uses [PAdESService.extendDocument] to promote a signed PDF to any higher PAdES level:
 * - **B-T**: embeds an RFC 3161 document timestamp (requires a TSA endpoint).
 * - **B-LT**: additionally embeds CRL/OCSP revocation data.
 * - **B-LTA**: additionally applies an archival document timestamp covering the revocation data.
 *
 * All target levels ≥ B-T require a TSA endpoint in the resolved configuration.
 */
class DssArchivingRepository(
	private val configRepository: ConfigRepository,
	private val credentialStore: CredentialStore
) : ArchivingRepository {

	@Suppress("TooGenericExceptionCaught", "ReturnCount")
	override suspend fun extendDocument(parameters: ArchivingParameters): OperationResult<ArchivingResult> {
		return try {
			val inputFile = File(parameters.inputFile)
			if (!inputFile.exists()) {
				return ArchivingError.ExtensionFailed(
					message = "Input file not found: ${parameters.inputFile}"
				).left()
			}

			val config = configRepository.getCurrentConfig()
			val resolvedConfig = parameters.resolvedConfig ?: ResolvedConfig.resolve(
				global = config.global,
				profile = config.activeProfile?.let { config.profiles[it] },
				operationOverrides = null
			)

			if (parameters.targetLevel == SignatureLevel.PADES_BASELINE_B) {
				return ArchivingError.ExtensionFailed(
					message = "Cannot extend to B-B: target level must be higher than the current level"
				).left()
			}

			val tsConfig = resolvedConfig.timestampServer
				?: return ArchivingError.ExtensionFailed(
					message = "A timestamp server must be configured for extension to ${parameters.targetLevel}"
				).left()

			val dssLevel = parameters.targetLevel.toDss()
			val service = buildExtendService(resolvedConfig, tsConfig)
			val extendParams = PAdESSignatureParameters().apply { setSignatureLevel(dssLevel) }
			val extendedDocument = service.extendDocument(FileDocument(inputFile), extendParams)

			val outputFile = File(parameters.outputFile).also { it.parentFile?.mkdirs() }
			withContext(Dispatchers.IO) { extendedDocument.writeTo(outputFile.outputStream()) }

			ArchivingResult(
				outputFile = parameters.outputFile,
				newSignatureLevel = parameters.targetLevel.name
			).right()
		} catch (e: Exception) {
			val isRevocationError = e.message?.let {
				it.contains("revocation", ignoreCase = true) ||
						it.contains("OCSP", ignoreCase = true) ||
						it.contains("CRL", ignoreCase = true)
			} ?: false

			if (isRevocationError) {
				ArchivingError.RevocationInfoError(
					message = "Failed to obtain revocation information",
					details = e.message,
					cause = e
				).left()
			} else {
				ArchivingError.ExtensionFailed(
					message = "Document extension failed",
					details = e.message,
					cause = e
				).left()
			}
		}
	}

	@Suppress("TooGenericExceptionCaught", "ReturnCount")
	override suspend fun needsArchivalRenewal(filePath: String): OperationResult<Boolean> {
		return try {
			val file = File(filePath)
			if (!file.exists()) {
				return ArchivingError.ExtensionFailed(
					message = "File not found: $filePath"
				).left()
			}

			val document = FileDocument(file)
			val validator = PDFDocumentValidator(document).apply {
				setCertificateVerifier(CommonCertificateVerifier())
			}
			val diagnosticData = validator.validateDocument().diagnosticData
			val renewalThreshold = Instant.now().plusSeconds(RENEWAL_THRESHOLD_SECONDS)

			val needsRenewal = diagnosticData.getTimestampList().any { ts ->
				val notAfter = ts.signingCertificate?.getNotAfter() ?: return@any false
				notAfter.toInstant().isBefore(renewalThreshold)
			}

			needsRenewal.right()
		} catch (e: Exception) {
			ArchivingError.ExtensionFailed(
				message = "Failed to check archival renewal status",
				details = e.message,
				cause = e
			).left()
		}
	}

	/**
	 * Build a [PAdESService] wired for document extension with revocation and TSA sources.
	 */
	private fun buildExtendService(config: ResolvedConfig, tsConfig: TimestampServerConfig): PAdESService =
		PAdESService(DssServiceFactory.buildCertificateVerifier(config)).apply {
			setPdfObjFactory(DssServiceFactory.buildPdfObjectFactory())
			setTspSource(DssServiceFactory.buildTspSource(tsConfig, credentialStore))
		}

	private companion object {
		/** Timestamps expiring within 90 days are considered in need of renewal. */
		const val RENEWAL_THRESHOLD_SECONDS = 90L * 24 * 3600
	}
}
