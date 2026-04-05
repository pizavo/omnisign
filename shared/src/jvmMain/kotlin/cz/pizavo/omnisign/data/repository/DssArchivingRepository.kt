package cz.pizavo.omnisign.data.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.util.toKotlinInstant
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.pades.signature.PAdESService
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

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
	private val dssServiceFactory: DssServiceFactory,
	private val warningSanitizer: DssWarningSanitizer,
	private val tspErrorDetector: TspErrorDetector,
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
			).getOrElse { error ->
				return ArchivingError.ExtensionFailed(
					message = error.message
				).left()
			}
			
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
			val statusAlert = CollectingStatusAlert()
			val logCapture = DssLogCapture()
			val (service, tlWarnings) = buildExtendService(resolvedConfig, tsConfig, statusAlert)
			val extendParams = PAdESSignatureParameters().apply { setSignatureLevel(dssLevel) }
			logCapture.start()
			try {
				val extendedDocument = service.extendDocument(FileDocument(inputFile), extendParams)
				
				val warnings = tlWarnings + statusAlert.drain() + logCapture.stop()
				val sanitized = warningSanitizer.sanitize(warnings)
				
				val outputFile = File(parameters.outputFile).also { it.parentFile?.mkdirs() }
				withContext(Dispatchers.IO) { outputFile.outputStream().use { extendedDocument.writeTo(it) } }
				
				ArchivingResult(
					outputFile = parameters.outputFile,
					newSignatureLevel = parameters.targetLevel.name,
					annotatedWarnings = sanitized.annotatedSummaries,
					rawWarnings = sanitized.raw,
				).right()
			} finally {
				logCapture.stop()
			}
		} catch (e: Exception) {
			if (tspErrorDetector.isTspException(e)) {
				val tsaUrl = (parameters.resolvedConfig ?: ResolvedConfig.resolve(
					global = configRepository.getCurrentConfig().global,
					profile = null,
					operationOverrides = null
				).getOrNull())?.timestampServer?.url
				return ArchivingError.TimestampFailed(
					message = tspErrorDetector.buildUserMessage(e, tsaUrl),
					details = e.message,
					cause = e,
				).left()
			}
			
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
	override suspend fun needsArchivalRenewal(
		filePath: String,
		renewalBufferDays: Int,
	): OperationResult<Boolean> {
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
			val renewalThreshold = Clock.System.now() + renewalBufferDays.days
			
			val needsRenewal = diagnosticData.getTimestampList().any { ts ->
				val notAfter = ts.signingCertificate?.notAfter ?: return@any false
				notAfter.toKotlinInstant() < renewalThreshold
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
	
	@Suppress("TooGenericExceptionCaught")
	override suspend fun getDocumentTimestampInfo(filePath: String): OperationResult<DocumentTimestampInfo> {
		return try {
			val file = File(filePath)
			if (!file.exists()) {
				return ArchivingError.ExtensionFailed(
					message = "File not found: $filePath"
				).left()
			}
			
			Loader.loadPDF(file).use { pdf ->
				val hasDocumentTimestamp = pdf.signatureDictionaries.any { sig ->
					sig.subFilter == PADES_TIMESTAMP_SUBFILTER
				}
				
				val hasDssDictionary = pdf.documentCatalog
					.cosObject.containsKey(COSName.getPDFName(DSS_DICTIONARY_KEY))
				
				val containsLtData = hasDocumentTimestamp || hasDssDictionary
				
				DocumentTimestampInfo(
					hasDocumentTimestamp = hasDocumentTimestamp,
					containsLtData = containsLtData,
				).right()
			}
		} catch (e: Exception) {
			ArchivingError.ExtensionFailed(
				message = "Failed to inspect document timestamp state",
				details = e.message,
				cause = e
			).left()
		}
	}
	
	/**
	 * Build a [PAdESService] wired for document extension with revocation and TSA sources.
	 *
	 * Loads EU LOTL and custom trusted-list sources so that TSA and certificate chains
	 * are properly trusted during the extension operation.
	 *
	 * @param statusAlert A [CollectingStatusAlert] that will capture verifier warnings
	 *   fired during the extension operation.
	 * @return A pair of the wired [PAdESService] and any TL-loading warnings.
	 */
	private fun buildExtendService(
		config: ResolvedConfig,
		tsConfig: TimestampServerConfig,
		statusAlert: CollectingStatusAlert,
	): Pair<PAdESService, List<String>> {
		val (cv, tlWarnings) = dssServiceFactory.buildSigningCertificateVerifier(config) { statusAlert }
		val service = PAdESService(cv).apply {
			setPdfObjFactory(dssServiceFactory.buildPdfObjectFactory())
			setTspSource(dssServiceFactory.buildTspSource(tsConfig))
		}
		return service to tlWarnings
	}
	
	companion object {
		/**
		 * PDF SubFilter value identifying a PAdES document timestamp (RFC 3161).
		 */
		private const val PADES_TIMESTAMP_SUBFILTER = "ETSI.RFC3161"
		
		/**
		 * PDF catalog key for the DSS dictionary that carries CRL/OCSP revocation data
		 * in PAdES-BASELINE-LT and higher.
		 */
		private const val DSS_DICTIONARY_KEY = "DSS"
	}
}

